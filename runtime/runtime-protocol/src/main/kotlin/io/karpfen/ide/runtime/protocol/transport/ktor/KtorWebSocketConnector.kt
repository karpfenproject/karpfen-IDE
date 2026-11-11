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
package io.karpfen.ide.runtime.protocol.transport.ktor

import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WebSocket seam over a Ktor [client] with the WebSockets plugin. It opens a session to the URL and wraps it in a
 * [KtorWebSocketLink]. [TransportFailures] classifies a failure to open. A cancellation of the caller is rethrown.
 *
 * @param client the Ktor client, shared with the HTTP seam. Its owner closes it.
 */
internal class KtorWebSocketConnector(private val client: HttpClient) : WebSocketConnector {

    override suspend fun connect(url: String): ConnectOutcome =
        try {
            ConnectOutcome.Connected(KtorWebSocketLink(client.webSocketSession(url)))
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            ConnectOutcome.Failed(TransportFailures.classify(cancelled))
        } catch (failure: Exception) {
            ConnectOutcome.Failed(TransportFailures.classify(failure))
        }
}

/**
 * One open Ktor WebSocket session as a link. A forwarding coroutine in the session's scope maps the session's text and
 * binary frames into [incoming]. The channel is unbounded, so the coroutine never suspends on the reader. When the
 * session's frames end, the coroutine completes [closure] from the session's close reason, which is null without a
 * close frame. Only then does it close [incoming]. So a reader that sees the end of the frames finds the closure
 * complete. The coroutine starts atomically, so it reaches this cleanup even when the session's scope was cancelled
 * before it started.
 *
 * A send that fails with a cancellation while the caller is still active reports [TransportFailure.Interrupted]. A
 * close of a link that has already closed does nothing, and a close that fails is only logged at debug level.
 *
 * @param session the open session.
 */
internal class KtorWebSocketLink(private val session: DefaultClientWebSocketSession) : WebSocketLink {

    /** The frames the runtime sent. */
    private val frames = Channel<LinkFrame>(Channel.UNLIMITED)

    /** How the link closed. */
    private val closed = CompletableDeferred<LinkClosure>()

    /** Whether the client closed the link. */
    private val closedByClient = AtomicBoolean(false)

    override val incoming: ReceiveChannel<LinkFrame> get() = frames

    override val closure: Deferred<LinkClosure> get() = closed

    init {
        session.launch(start = CoroutineStart.ATOMIC) {
            try {
                for (frame in session.incoming) {
                    WebSocketFrames.linkFrameOf(frame)?.let { frames.trySend(it) }
                }
            } finally {
                val reason = withContext(NonCancellable) {
                    try {
                        session.closeReason.await()
                    } catch (failure: Exception) {
                        logger.debug("The WebSocket session ended without a close reason", failure)
                        null
                    }
                }
                closed.complete(WebSocketFrames.closureOf(reason, closedByClient.get()))
                frames.close()
            }
        }
    }

    override suspend fun send(text: String): LinkSendResult =
        try {
            session.send(Frame.Text(text))
            LinkSendResult.Sent
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            LinkSendResult.Failed(TransportFailure.Interrupted(TransportFailures.describe(cancelled)))
        } catch (failure: Exception) {
            LinkSendResult.Failed(TransportFailures.classify(failure))
        }

    override suspend fun close(code: Int, reason: String) {
        if (closed.isCompleted) return
        closedByClient.set(true)
        try {
            session.close(WebSocketFrames.closeReasonOf(code, reason))
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (failure: Exception) {
            logger.debug("Closing the WebSocket session failed; it is closed already", failure)
        }
    }

    private companion object {
        /** The logger of session ends without a close reason and of failed closes, at debug level. */
        private val logger = LoggerFactory.getLogger(KtorWebSocketLink::class.java)
    }
}
