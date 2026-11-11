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
package io.karpfen.ide.runtime.protocol.fake

import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One WebSocket that the client opened to the [FakeRuntime]. The test plays the runtime's side. It sends frames with
 * [push], closes the link with [closeByRuntime], or drops it without a close frame with [drop]. The link records what
 * the client sent. The first text is the handshake, and the texts after it are the client's frames.
 *
 * The link answers the handshake only if the test programmed a close for it with [FakeRuntime.closeHandshake]. The link
 * then closes itself with that code and reason as soon as the handshake arrived, the way the runtime refuses a
 * handshake. Any other handshake is accepted silently, the way the runtime accepts one. Nothing else happens by itself.
 * The link never answers, checks or forwards a frame of the client.
 *
 * Every function may be called from any thread. [incoming] has one collector.
 *
 * @property url the URL the client connected to.
 * @param handshakeClose the close that answers the handshake, or null to accept it silently.
 */
public class FakeWebSocketLink internal constructor(
    public val url: String,
    private val handshakeClose: FakeClose?,
) : WebSocketLink {

    /** Guards the records and the closing. */
    private val lock = Any()

    /** The frames the test pushed and the client has not taken yet. */
    private val inbox = Channel<LinkFrame>(Channel.UNLIMITED)

    /** How the link closed, once it did. */
    private val closed = CompletableDeferred<LinkClosure>()

    /** The first text the client sent. */
    private var firstText: String? = null

    /** The texts the client sent after the handshake. */
    private val clientTexts = ArrayList<String>()

    /** The closes the client asked for. */
    private val clientCloseRecords = ArrayList<FakeClose>()

    override val incoming: ReceiveChannel<LinkFrame> get() = inbox

    override val closure: Deferred<LinkClosure> get() = closed

    /** The handshake, which is the first text the client sent, or null before it sent one. */
    public val handshake: String? get() = synchronized(lock) { firstText }

    /** The texts the client sent after the handshake, in order. */
    public val clientFrames: List<String> get() = synchronized(lock) { clientTexts.toList() }

    /** The closes the client asked for, in order. */
    public val clientCloses: List<FakeClose> get() = synchronized(lock) { clientCloseRecords.toList() }

    /** True while the link is open. */
    public val isOpen: Boolean get() = !closed.isCompleted

    /** True if the client took every frame the test pushed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val drained: Boolean get() = inbox.isEmpty

    /** True if the handshake names the client [clientId], which means it starts with the id and the separator. */
    public fun belongsTo(clientId: String): Boolean = handshake?.startsWith(clientId + WireNames.HANDSHAKE_SEPARATOR) == true

    /** Sends the text frame [frame] as the runtime. Returns false if the link is closed and the frame went nowhere. */
    public fun push(frame: String): Boolean = deliver(LinkFrame.Text(frame))

    /** Sends a binary frame of [size] bytes as the runtime. Returns false if the link is closed. */
    public fun pushBinary(size: Int): Boolean = deliver(LinkFrame.Binary(size))

    /** Closes the link as the runtime, with the close frame [code] and [reason]. Does nothing if already closed. */
    public fun closeByRuntime(code: Int, reason: String) {
        end(LinkClosure(code, reason, byClient = false))
    }

    /**
     * Breaks the connection without a close frame, the way a dropped network or a half-open socket ends. Does nothing
     * if the link is closed already.
     */
    public fun drop() {
        end(LinkClosure(code = null, reason = null, byClient = false))
    }

    override suspend fun send(text: String): LinkSendResult {
        val refusal = synchronized(lock) {
            when {
                closed.isCompleted -> return LinkSendResult.Failed(TransportFailure.Interrupted(CLOSED))
                firstText == null -> {
                    firstText = text
                    handshakeClose
                }
                else -> {
                    clientTexts += text
                    null
                }
            }
        }
        refusal?.let { closeByRuntime(it.code, it.reason) }
        return LinkSendResult.Sent
    }

    override suspend fun close(code: Int, reason: String) {
        synchronized(lock) { clientCloseRecords += FakeClose(code, reason) }
        end(LinkClosure(code, reason, byClient = true))
    }

    /** Hands [frame] to the client if the link is open. */
    private fun deliver(frame: LinkFrame): Boolean =
        synchronized(lock) { !closed.isCompleted && inbox.trySend(frame).isSuccess }

    /** Completes the closure with [closure], once, and then ends the frames. */
    private fun end(closure: LinkClosure) {
        synchronized(lock) {
            if (!closed.complete(closure)) return
            inbox.close()
        }
    }

    private companion object {
        /** Why a send on a closed link failed. */
        const val CLOSED = "the link is closed"
    }
}
