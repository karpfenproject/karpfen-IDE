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
package io.karpfen.ide.runtime.protocol.conformance

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.DecodedFrame
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.RuntimeFrameDecoder
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * Keeps every frame a real runtime sends on the links of one scenario, so that after the scenario every frame can be
 * decoded with the client's [RuntimeFrameDecoder]: the broadest check that the runtime's writers and the client's reader
 * still agree. Each link is recorded with its handshake, the first text the client sent on it, which names the
 * environment and the client the frames belong to.
 *
 * Threading: links may be opened and used from any coroutine.
 */
internal class FrameRecorder {

    /** Guards [links]. */
    private val lock = Any()

    /** The recorded links, in the order they were opened. */
    private val links = ArrayList<LinkRecord>()

    /**
     * [transports] with a WebSocket seam whose links are recorded; closing them closes [transports]. The links forward
     * the runtime's frames from coroutines in [scope], which must live as long as the scenario.
     */
    fun recording(transports: RuntimeTransports, scope: CoroutineScope): RuntimeTransports =
        RuntimeTransports(transports.http, RecordingConnector(transports.webSockets, scope), transports::close)

    /**
     * The violations of the recorded frames, one line each: frames the decoder of their link rejects or reports,
     * binary frames, and frames on a link whose handshake names no environment and client.
     */
    fun violations(): List<String> = synchronized(lock) { links.toList() }.flatMap { it.violations() }

    /** Records the links of [connector], forwarding their frames in [scope]. */
    private inner class RecordingConnector(private val connector: WebSocketConnector, private val scope: CoroutineScope) :
        WebSocketConnector {
        override suspend fun connect(url: String): ConnectOutcome =
            when (val outcome = connector.connect(url)) {
                is ConnectOutcome.Connected -> {
                    val record = LinkRecord(url)
                    synchronized(lock) { links += record }
                    ConnectOutcome.Connected(RecordingLink(outcome.link, record, scope))
                }
                is ConnectOutcome.Failed -> outcome
            }
    }
}

/**
 * What one link carried: its handshake and the frames the runtime sent, in order.
 *
 * @property url the URL of the link.
 */
private class LinkRecord(private val url: String) {

    /** Guards the records. */
    private val lock = Any()

    /** The first text the client sent. */
    private var handshake: String? = null

    /** The frames the runtime sent. */
    private val frames = ArrayList<LinkFrame>()

    /** Records a text the client sent. */
    fun sent(text: String) {
        synchronized(lock) { if (handshake == null) handshake = text }
    }

    /** Records a frame the runtime sent. */
    fun received(frame: LinkFrame) {
        synchronized(lock) { frames += frame }
    }

    /** The violations of the frames of this link, decoded for the environment and client of its handshake. */
    fun violations(): List<String> {
        val (shake, received) = synchronized(lock) { handshake to frames.toList() }
        if (received.isEmpty()) return emptyList()
        val decoder = decoderFor(shake)
            ?: return listOf("$url: ${received.size} frames on a link whose handshake '$shake' names no client")
        return received.flatMap { frame ->
            when (frame) {
                is LinkFrame.Binary -> listOf("$url: a binary frame of ${frame.size} bytes")
                is LinkFrame.Text -> when (val decoded = decoder.decode(frame.text)) {
                    is DecodedFrame.Push -> decoded.violations.map { describe(it) }
                    is DecodedFrame.Violation -> listOf(describe(decoded.violation))
                }
            }
        }
    }

    /** One line about [violation] on this link. */
    private fun describe(violation: ProtocolViolation): String = "$url: ${violation.kind} ${violation.detail}: ${violation.excerpt}"

    /** The decoder of the environment and client the [handshake] names, or null if it names none. */
    private fun decoderFor(handshake: String?): RuntimeFrameDecoder? {
        val parts = handshake?.split(WireNames.HANDSHAKE_SEPARATOR) ?: return null
        if (parts.size != HANDSHAKE_PARTS) return null
        return try {
            RuntimeFrameDecoder(EnvironmentKey(parts[1]), ClientId(parts[0]))
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        /** The parts of a handshake: client id, environment key, access key. */
        const val HANDSHAKE_PARTS = 3
    }
}

/**
 * A link that records what passes through [link] into [record]: the texts the client sends, and the frames the runtime
 * sends, which a coroutine in [scope] forwards into [incoming] without ever suspending on the reader. The closure is the
 * recorded link's, complete before [incoming] ends.
 */
private class RecordingLink(
    private val link: WebSocketLink,
    private val record: LinkRecord,
    scope: CoroutineScope,
) : WebSocketLink {

    /** The frames forwarded to the client. */
    private val forwarded = Channel<LinkFrame>(Channel.UNLIMITED)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (frame in link.incoming) {
                    record.received(frame)
                    forwarded.trySend(frame)
                }
            } finally {
                forwarded.close()
            }
        }
    }

    override val incoming: ReceiveChannel<LinkFrame> get() = forwarded

    override val closure: Deferred<LinkClosure> get() = link.closure

    override suspend fun send(text: String): LinkSendResult =
        link.send(text).also { if (it == LinkSendResult.Sent) record.sent(text) }

    override suspend fun close(code: Int, reason: String) {
        link.close(code, reason)
    }
}
