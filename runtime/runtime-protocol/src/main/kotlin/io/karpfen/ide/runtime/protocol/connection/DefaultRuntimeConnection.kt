/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.runtime.protocol.connection

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.LifecycleOperation
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.DecodedFrame
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventFrameEncoder
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.RuntimeFrameDecoder
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * The push channel of [clientId] to the environment of [handle]. One supervisor coroutine runs it, in a supervisor
 * child scope of [parent] that is named after the client id.
 *
 * The supervisor coroutine is the only writer of [state]. It makes attempts until a link is open. Each attempt takes a
 * fresh key from [issuer] and closes the previous link with a normal close, because the runtime keeps pushing to the
 * first session of a client id. Then it connects to [webSocketUrl] through [connector] and sends the handshake
 * `<clientId>:<envKey>:<key>`. Nothing acknowledges the handshake, so the link counts as open as soon as the handshake
 * was sent. A failed attempt is retried after the [policy]'s delay, up to its limit. While a link is open, one reader
 * coroutine decodes its frames synchronously. It hands the events on without suspending, into an unbounded channel, so
 * this client never stalls the runtime's single broadcaster.
 *
 * The connection ends when the runtime closes the link with 1003, which means a rejected handshake, whenever that close
 * comes. It ends with the close `Environment stopped`, and when the handle's phase becomes stopped. It ends when the
 * runtime rejects a key issuance for an unknown environment, and when the handle's phase becomes lost. It ends with
 * [disconnect] or [close], and with a cancellation of [parent]'s scope, also before the first [open]. It also ends
 * after the policy's limit of failed attempts in a row. Any other close the client did not ask for is an interruption,
 * and a reconnect follows after the policy's delay.
 *
 * @param handle the environment's handle. Its phase can end the connection, and its verdict guards [send].
 * @param clientId the client id of every attempt.
 * @param webSocketUrl where the runtime's WebSocket is.
 * @param connector the WebSocket seam.
 * @param issuer issues the key of every attempt.
 * @param policy the delays between attempts and their limit.
 * @param parent the scope whose child the connection's scope is.
 * @param dispatcher where the connection runs its work, delays and backoff included.
 */
internal class DefaultRuntimeConnection(
    private val handle: RuntimeEnvironment,
    override val clientId: ClientId,
    private val webSocketUrl: String,
    private val connector: WebSocketConnector,
    private val issuer: AccessKeyIssuer,
    private val policy: ReconnectPolicy,
    parent: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) : RuntimeConnection {

    override val environment: EnvironmentKey = handle.key

    /** The connection's scope. It is a supervisor child of the parent's scope and is named after the client id. */
    private val scope = CoroutineScope(
        parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineName("$NAME_PREFIX${clientId.value}") +
            dispatcher,
    )

    /** The state. The supervisor coroutine writes it, and whoever ends the connection sets the end once. */
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** The events in order. The channel is unbounded, so no writer ever suspends on the single collector. */
    private val channel = Channel<ConnectionEvent>(Channel.UNLIMITED)

    /** The link of the current or the last attempt, until it is closed by the client. */
    private val currentLink = AtomicReference<WebSocketLink?>(null)

    /** Completed with the end the client asked for, by [disconnect] or [close]. */
    private val endRequest = CompletableDeferred<ConnectionEnd>()

    /** Serializes the frames the client writes, which are the handshake and the events. */
    private val sendMutex = Mutex()

    /** Whether [close] ran. */
    private val closed = AtomicBoolean(false)

    /** Decodes the frames of every link of this connection. */
    private val decoder = RuntimeFrameDecoder(environment, clientId)

    /**
     * The supervisor coroutine, started by the first [open]. Its completion ends the connection as disconnected if it
     * has not ended, which matters only when a cancellation of the connection's scope completes it before it started.
     */
    private val supervisor: Job = scope.launch(start = CoroutineStart.LAZY) { supervise() }
        .also { job -> job.invokeOnCompletion { finish(ConnectionEnd.Disconnected) } }

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    override val events: Flow<ConnectionEvent> = channel.receiveAsFlow()

    override suspend fun open(): ConnectionState = withContext(dispatcher) {
        supervisor.start()
        state.first { it.isSettled() }
    }

    override suspend fun awaitOpen(within: Duration): ConnectionState = withContext(dispatcher) {
        withTimeoutOrNull(within) { state.first { it.isSettled() } } ?: state.value
    }

    override suspend fun send(frame: EventFrame): SendOutcome {
        handle.allows(LifecycleOperation.INJECT_EVENT)?.let { return SendOutcome.NotAllowed(it) }
        return sendMutex.withLock {
            val current = state.value
            val link = currentLink.get()
            if (current !is ConnectionState.Open || link == null) {
                SendOutcome.NotConnected(current)
            } else {
                val text = EventFrameEncoder.encode(frame)
                when (val result = link.send(text)) {
                    LinkSendResult.Sent -> SendOutcome.Written(text)
                    is LinkSendResult.Failed -> SendOutcome.Failed(result.failure)
                }
            }
        }
    }

    override suspend fun disconnect() {
        withContext(NonCancellable + dispatcher) {
            endRequest.complete(ConnectionEnd.Disconnected)
            supervisor.start()
            supervisor.join()
            closeCurrentLink()
            finish(ConnectionEnd.Disconnected)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        endRequest.complete(ConnectionEnd.Disconnected)
        finish(ConnectionEnd.Disconnected)
        scope.cancel()
    }

    /**
     * The body of the supervisor coroutine. It runs the attempts until the connection ends by itself, by the handle's
     * phase or by the client's request. Then it closes the current link and ends the connection. A cancellation of the
     * connection's scope closes the link without suspending the cancellation and ends the connection as disconnected.
     */
    private suspend fun supervise() {
        try {
            val end = if (endRequest.isCompleted) {
                endRequest.await()
            } else {
                coroutineScope {
                    val attempts = async { attempt() }
                    val phaseEnd = async { phaseEnd() }
                    val end = select {
                        endRequest.onAwait { it }
                        phaseEnd.onAwait { it }
                        attempts.onAwait { it }
                    }
                    attempts.cancel()
                    phaseEnd.cancel()
                    end
                }
            }
            withContext(NonCancellable) { closeCurrentLink() }
            finish(end)
        } finally {
            withContext(NonCancellable) { closeCurrentLink() }
            finish(ConnectionEnd.Disconnected)
        }
    }

    /** Suspends until the handle's phase is stopped or lost, and returns the end that follows from it. */
    private suspend fun phaseEnd(): ConnectionEnd =
        when (val phase = handle.phase.first { it is EnvironmentPhase.Stopped || it is EnvironmentPhase.Lost }) {
            is EnvironmentPhase.Lost -> ConnectionEnd.EnvironmentLost(phase.reason)
            else -> ConnectionEnd.EnvironmentStopped
        }

    /**
     * Makes attempts and runs the links they open until one of them ends the connection, and returns that end. An
     * attempt that fails counts towards the policy's limit. An open link resets the count.
     */
    private suspend fun attempt(): ConnectionEnd {
        var epoch = 0
        var failures = 0
        var retries = 0
        while (true) {
            mutableState.update(ConnectionState.Connecting(failures + 1))
            when (val result = attemptOnce()) {
                is Attempt.Ended -> return result.end
                is Attempt.Failed -> {
                    failures++
                    logger.debug("Connection of {} to {}: attempt {} failed: {}", clientId, environment, failures, result.reason)
                    if (failures >= policy.maxAttempts) return ConnectionEnd.GaveUp(failures, result.reason)
                    mutableState.update(ConnectionState.Reconnecting(failures + 1, null))
                }
                is Attempt.Opened -> {
                    epoch++
                    failures = 0
                    retries = 0
                    mutableState.update(ConnectionState.Open(epoch))
                    channel.trySend(ConnectionEvent.Opened(epoch))
                    val closure = runLink(result.link)
                    endOf(closure)?.let { return it }
                    channel.trySend(ConnectionEvent.Interrupted(closure))
                    mutableState.update(ConnectionState.Reconnecting(1, closure))
                }
            }
            retries++
            delay(policy.delayBefore(retries))
        }
    }

    /**
     * Makes one attempt. It takes a fresh key, closes the previous link, opens a new link and sends the handshake. A
     * key issuance that shows the environment stopped or lost ends the connection. A runtime rejection for an unknown
     * environment counts as lost. Any other refusal or failure only fails the attempt.
     */
    private suspend fun attemptOnce(): Attempt {
        val key = when (val issued = issuer.issue()) {
            is CallOutcome.Done -> issued.value
            is CallOutcome.Refused -> return when (val phase = issued.refusal.phase) {
                EnvironmentPhase.Stopped -> Attempt.Ended(ConnectionEnd.EnvironmentStopped)
                is EnvironmentPhase.Lost -> Attempt.Ended(ConnectionEnd.EnvironmentLost(phase.reason))
                else -> Attempt.Failed("the access key was refused: ${issued.refusal.reason}")
            }
            is CallOutcome.Failed -> return if (issued.failure.isUnknownEnvironment()) {
                Attempt.Ended(ConnectionEnd.EnvironmentLost(issued.failure.describe()))
            } else {
                Attempt.Failed(issued.failure.describe())
            }
        }
        closeCurrentLink()
        val link = when (val connected = connector.connect(webSocketUrl)) {
            is ConnectOutcome.Connected -> connected.link
            is ConnectOutcome.Failed -> return Attempt.Failed("connecting to $webSocketUrl failed: ${connected.failure.cause}")
        }
        currentLink.set(link)
        val sent = sendMutex.withLock { link.send(handshake(key)) }
        return when (sent) {
            LinkSendResult.Sent -> Attempt.Opened(link)
            is LinkSendResult.Failed -> {
                closeCurrentLink()
                Attempt.Failed("sending the handshake failed: ${sent.failure.cause}")
            }
        }
    }

    /**
     * Runs one open [link]. A reader coroutine decodes its frames into events until the link's frames end. Then the
     * function returns the link's closure.
     */
    private suspend fun runLink(link: WebSocketLink): LinkClosure {
        coroutineScope {
            launch { read(link) }
        }
        return link.closure.await()
    }

    /**
     * Decodes the frames of [link] and hands the events on without suspending. A binary frame becomes a violation of
     * the kind [ViolationKind.BINARY_FRAME].
     */
    private suspend fun read(link: WebSocketLink) {
        for (frame in link.incoming) {
            when (frame) {
                is LinkFrame.Text -> when (val decoded = decoder.decode(frame.text)) {
                    is DecodedFrame.Push -> {
                        decoded.violations.forEach { channel.trySend(ConnectionEvent.Violated(it)) }
                        channel.trySend(ConnectionEvent.Received(decoded.push))
                    }
                    is DecodedFrame.Violation -> channel.trySend(ConnectionEvent.Violated(decoded.violation))
                }
                is LinkFrame.Binary -> channel.trySend(
                    ConnectionEvent.Violated(
                        ProtocolViolation(ViolationKind.BINARY_FRAME, "the runtime sent a binary frame of ${frame.size} bytes", ""),
                    ),
                )
            }
        }
    }

    /** The end that [closure] means, or null if it is an interruption to reconnect after. */
    private fun endOf(closure: LinkClosure): ConnectionEnd? =
        when {
            closure.code == WireNames.CLOSE_CANNOT_ACCEPT -> ConnectionEnd.HandshakeRejected(rejectionOf(closure))
            closure.code == WireNames.CLOSE_NORMAL && closure.reason == WireNames.REASON_ENVIRONMENT_STOPPED ->
                ConnectionEnd.EnvironmentStopped
            else -> null
        }

    /** The handshake rejection that a close 1003 with the reason of [closure] means. */
    private fun rejectionOf(closure: LinkClosure): HandshakeRejection =
        when (closure.reason) {
            WireNames.REASON_FIRST_MESSAGE_NOT_TEXT -> HandshakeRejection.FirstMessageNotText
            WireNames.REASON_INVALID_AUTH_FORMAT -> HandshakeRejection.InvalidAuthFormat
            WireNames.REASON_INVALID_CREDENTIALS -> HandshakeRejection.InvalidCredentials
            else -> HandshakeRejection.Other(closure.code ?: WireNames.CLOSE_CANNOT_ACCEPT, closure.reason)
        }

    /** The handshake text of an attempt with [key]. */
    private fun handshake(key: AccessKey): String =
        listOf(clientId.value, environment.value, key.value).joinToString(WireNames.HANDSHAKE_SEPARATOR)

    /** Closes the current link with a normal close, if there is one, and forgets it. */
    private suspend fun closeCurrentLink() {
        currentLink.getAndSet(null)?.close(WireNames.CLOSE_NORMAL, CLIENT_CLOSE_REASON)
    }

    /**
     * Ends the connection with [end], unless it ended already. It sets the state, sends the last event and closes the
     * event channel.
     */
    private fun finish(end: ConnectionEnd) {
        while (true) {
            val current = mutableState.value
            if (current is ConnectionState.Ended) return
            if (mutableState.compareAndSet(current, ConnectionState.Ended(end))) break
        }
        logger.debug("Connection of {} to {} ended: {}", clientId, environment, end)
        channel.trySend(ConnectionEvent.Ended(end))
        channel.close()
    }

    /** Sets the state to [next], unless the connection ended. */
    private fun MutableStateFlow<ConnectionState>.update(next: ConnectionState) {
        while (true) {
            val current = value
            if (current is ConnectionState.Ended || compareAndSet(current, next)) return
        }
    }

    /** True for the states [open] and [awaitOpen] wait for. */
    private fun ConnectionState.isSettled(): Boolean = this is ConnectionState.Open || this is ConnectionState.Ended

    /** True if this failure is the runtime's rejection of an unknown environment. */
    private fun RuntimeFailure.isUnknownEnvironment(): Boolean =
        this is RuntimeFailure.Rejected && kind == RejectionKind.UNKNOWN_ENVIRONMENT

    /** How one attempt ended. */
    private sealed interface Attempt {
        /** A link is open and the handshake was sent. */
        data class Opened(val link: WebSocketLink) : Attempt

        /** The attempt failed. Another may follow. */
        data class Failed(val reason: String) : Attempt

        /** The attempt showed that the connection must end. */
        data class Ended(val end: ConnectionEnd) : Attempt
    }

    private companion object {
        /** The logger of failed attempts and of the end of a connection, at debug level. */
        private val logger = LoggerFactory.getLogger(DefaultRuntimeConnection::class.java)

        /** The prefix of the name of a connection's scope. */
        const val NAME_PREFIX = "runtime-connection-"

        /** The reason of the client's own normal close. */
        const val CLIENT_CLOSE_REASON = "Client closed the connection"
    }
}
