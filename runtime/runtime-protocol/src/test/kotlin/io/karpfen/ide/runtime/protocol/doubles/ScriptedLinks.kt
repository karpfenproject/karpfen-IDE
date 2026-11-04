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
package io.karpfen.ide.runtime.protocol.doubles

import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A scripted WebSocket link: the test plays the runtime's side by sending frames and closing it, and reads what the
 * client sent and how it closed the link. Every step is also written to [journal], so a test can check the order of
 * steps across links and the connector.
 *
 * @property name how the link appears in the journal.
 * @property journal the shared record of steps.
 * @param sendFailure the failure every send reports from the start, the handshake included, or null if sends succeed.
 */
class ScriptedLink(
    private val name: String,
    private val journal: Journal,
    sendFailure: TransportFailure? = null,
) : WebSocketLink {

    /** The frames the runtime sent and the client has not taken yet. */
    private val frames = Channel<LinkFrame>(Channel.UNLIMITED)

    /** How the link closed, once it did. */
    private val closed = CompletableDeferred<LinkClosure>()

    /** The texts the client sent, in order. */
    private val sentTexts = ArrayList<String>()

    /** The closes the client asked for, in order. */
    private val clientCloses = ArrayList<Pair<Int, String>>()

    /** The failure every send reports from now on, or null if sends succeed. */
    @Volatile
    var sendFailure: TransportFailure? = sendFailure

    override val incoming: ReceiveChannel<LinkFrame> get() = frames

    override val closure: Deferred<LinkClosure> get() = closed

    /** The texts the client sent, the handshake first. */
    val sent: List<String> get() = synchronized(sentTexts) { sentTexts.toList() }

    /** The closes the client asked for, with code and reason. */
    val closes: List<Pair<Int, String>> get() = synchronized(clientCloses) { clientCloses.toList() }

    /** True if the frames the runtime sent were all taken by the client. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val drained: Boolean get() = frames.isEmpty

    /** The runtime sends the text frame [text]. */
    fun push(text: String) {
        frames.trySend(LinkFrame.Text(text))
    }

    /** The runtime sends a binary frame of [size] bytes. */
    fun pushBinary(size: Int) {
        frames.trySend(LinkFrame.Binary(size))
    }

    /** The runtime closes the link with [code] and [reason]. */
    fun closeByRuntime(code: Int, reason: String) {
        journal.add("$name closed by the runtime with $code")
        end(LinkClosure(code, reason, byClient = false))
    }

    /** The connection breaks without a close frame. */
    fun drop() {
        journal.add("$name dropped")
        end(LinkClosure(null, null, byClient = false))
    }

    override suspend fun send(text: String): LinkSendResult {
        sendFailure?.let { return LinkSendResult.Failed(it) }
        synchronized(sentTexts) { sentTexts += text }
        journal.add("$name sent $text")
        return LinkSendResult.Sent
    }

    override suspend fun close(code: Int, reason: String) {
        synchronized(clientCloses) { clientCloses += code to reason }
        journal.add("$name closed by the client with $code")
        end(LinkClosure(code, reason, byClient = true))
    }

    /** Completes the closure, once, and ends the frames. */
    private fun end(closure: LinkClosure) {
        closed.complete(closure)
        frames.close()
    }
}

/**
 * A scripted WebSocket connector: answers each connect with the next scripted outcome (a new [ScriptedLink], a failure,
 * or a connect that never completes) and records the URLs and the steps in the [journal].
 *
 * @property journal the shared record of steps.
 */
class ScriptedConnector(private val journal: Journal = Journal()) : WebSocketConnector {

    /** One scripted outcome of a connect. */
    private sealed interface Step {
        /**
         * A link opens.
         *
         * @property sendFailure the failure its sends report, or null if they succeed.
         */
        data class Link(val sendFailure: TransportFailure?) : Step

        /** The connect fails. */
        data class Failure(val failure: TransportFailure) : Step

        /** The connect never completes. */
        data object Hang : Step
    }

    /** The outcomes still scripted, in order. */
    private val steps = ArrayDeque<Step>()

    /** The links opened so far. */
    private val opened = ArrayList<ScriptedLink>()

    /** The URLs connected to so far. */
    private val urls = ArrayList<String>()

    /** The links opened so far, in order. */
    val links: List<ScriptedLink> get() = synchronized(opened) { opened.toList() }

    /** The URLs connected to, in order. */
    val connectedUrls: List<String> get() = synchronized(urls) { urls.toList() }

    /**
     * The next connect opens a link whose sends fail with [sendFailure], or succeed without one; once the script is
     * used up, every connect opens a link whose sends succeed.
     */
    fun link(sendFailure: TransportFailure? = null): ScriptedConnector =
        also { synchronized(steps) { steps.addLast(Step.Link(sendFailure)) } }

    /** The next connect fails with [failure]. */
    fun failure(failure: TransportFailure = TransportFailure.Unreachable("Connection refused")): ScriptedConnector =
        also { synchronized(steps) { steps.addLast(Step.Failure(failure)) } }

    /** The next connect never completes. */
    fun hang(): ScriptedConnector = also { synchronized(steps) { steps.addLast(Step.Hang) } }

    override suspend fun connect(url: String): ConnectOutcome {
        synchronized(urls) { urls += url }
        val step = synchronized(steps) { steps.removeFirstOrNull() } ?: Step.Link(sendFailure = null)
        journal.add("connect")
        return when (step) {
            is Step.Link -> {
                val link = synchronized(opened) {
                    ScriptedLink("link ${opened.size + 1}", journal, step.sendFailure).also { opened += it }
                }
                ConnectOutcome.Connected(link)
            }
            is Step.Failure -> ConnectOutcome.Failed(step.failure)
            Step.Hang -> awaitCancellation()
        }
    }
}

/** A record of the steps of scripted links and connectors, in the order they happened. */
class Journal {
    /** The steps, in order. */
    private val entries = ArrayList<String>()

    /** The steps so far. */
    val steps: List<String> get() = synchronized(entries) { entries.toList() }

    /** Records [step]. */
    fun add(step: String) {
        synchronized(entries) { entries += step }
    }
}
