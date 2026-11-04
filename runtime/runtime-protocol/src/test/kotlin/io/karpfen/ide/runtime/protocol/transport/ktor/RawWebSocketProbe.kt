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

import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * What the client's seams cannot do, done with Ktor directly for the live scenarios: the client's links only send text,
 * so a probe opens its own WebSocket to send a binary first frame, which the runtime refuses. Lives in the Ktor
 * adapter's package, the only one of the runtime modules that may use Ktor.
 */
internal object RawWebSocketProbe {

    /** The bytes of the binary first frame. */
    private val BINARY = byteArrayOf(1, 2, 3)

    /**
     * Opens a WebSocket to [webSocketUrl], sends a binary first frame and answers how the runtime closed the socket,
     * waiting at most [within].
     */
    suspend fun closureAfterBinaryFirstFrame(webSocketUrl: String, within: Duration): LinkClosure {
        HttpClient(CIO) { install(WebSockets) }.use { client ->
            val session = client.webSocketSession(webSocketUrl)
            session.send(Frame.Binary(true, BINARY))
            return withTimeout(within) { KtorWebSocketLink(session).closure.await() }
        }
    }
}
