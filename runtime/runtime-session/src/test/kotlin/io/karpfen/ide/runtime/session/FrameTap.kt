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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.DecodedFrame
import io.karpfen.ide.runtime.protocol.message.RuntimeFrameDecoder
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.transport.ConnectOutcome
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.protocol.transport.LinkFrame
import io.karpfen.ide.runtime.protocol.transport.LinkSendResult
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.karpfen.ide.runtime.protocol.transport.WebSocketConnector
import io.karpfen.ide.runtime.protocol.transport.WebSocketLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * Keeps every text frame the runtime sends on the links that a scenario's sessions open, together with each link's
 * handshake. So a scenario can check what a session received, and not only what it applied. A check of absence needs
 * proof that a push never came. The frames are handed on without suspending, so the tap never stalls the runtime.
 *
 * Links may be opened and used from any coroutine.
 *
 * @param scope where the forwarders of the links run.
 */
class FrameTap(private val scope: CoroutineScope) {
    /** Guards [links]. */
    private val lock = Any()

    /** The tapped links in the order they were opened. */
    private val links = ArrayList<TappedLink>()

    /** [transports] with a WebSocket seam whose links are tapped. The HTTP seam stays the one of [transports]. */
    fun tapping(transports: RuntimeTransports): RuntimeTransports =
        RuntimeTransports(http = transports.http, webSockets = TappedConnector(transports.webSockets))

    /**
     * Every push received on the links of [clientId] to [environment], in the order the links were opened and the
     * pushes came.
     */
    fun pushes(environment: EnvironmentKey, clientId: ClientId): List<RuntimePush> {
        val decoder = RuntimeFrameDecoder(environment, clientId)
        val prefix = clientId.value + WireNames.HANDSHAKE_SEPARATOR + environment.value + WireNames.HANDSHAKE_SEPARATOR
        val texts = synchronized(lock) { links.filter { it.handshake?.startsWith(prefix) == true }.flatMap { it.received() } }
        return texts.mapNotNull { (decoder.decode(it) as? DecodedFrame.Push)?.push }
    }

    /** The connector whose links are tapped. */
    private inner class TappedConnector(private val connector: WebSocketConnector) : WebSocketConnector {
        override suspend fun connect(url: String): ConnectOutcome =
            when (val outcome = connector.connect(url)) {
                is ConnectOutcome.Connected -> ConnectOutcome.Connected(TappedLink(outcome.link).also { synchronized(lock) { links += it } })
                is ConnectOutcome.Failed -> outcome
            }
    }

    /**
     * One tapped link. Its frames are kept and handed on. Its first sent text is kept as its handshake.
     *
     * @param link the link tapped.
     */
    private inner class TappedLink(private val link: WebSocketLink) : WebSocketLink {
        /** The frames handed on to the reader. */
        private val forwarded = Channel<LinkFrame>(Channel.UNLIMITED)

        /** The texts received, guarded by itself. */
        private val texts = ArrayList<String>()

        /** The first text sent. */
        @Volatile
        var handshake: String? = null
            private set

        init {
            scope.launch {
                try {
                    for (frame in link.incoming) {
                        if (frame is LinkFrame.Text) synchronized(texts) { texts += frame.text }
                        forwarded.trySend(frame)
                    }
                } finally {
                    forwarded.close()
                }
            }
        }

        /** The texts received so far. */
        fun received(): List<String> = synchronized(texts) { texts.toList() }

        override val incoming: ReceiveChannel<LinkFrame>
            get() = forwarded

        override val closure: Deferred<LinkClosure>
            get() = link.closure

        override suspend fun send(text: String): LinkSendResult {
            if (handshake == null) handshake = text
            return link.send(text)
        }

        override suspend fun close(code: Int, reason: String) {
            link.close(code, reason)
        }
    }
}
