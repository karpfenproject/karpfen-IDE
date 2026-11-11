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

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.transport.RuntimeTransports
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Creates the production transports to the runtime at [endpoint]. The HTTP seam and the WebSocket seam share one Ktor
 * client with the CIO engine. The client has the `HttpTimeout` plugin with the connect timeout and the request backstop
 * of [options], and the `WebSockets` plugin with the ping interval of [options]. It treats no status as an error, so
 * every answer reaches the gateway as an answer. Closing the transports closes the Ktor client. Creating them opens no
 * connection.
 */
public fun ktorTransports(endpoint: RuntimeEndpoint, options: KtorTransportOptions = KtorTransportOptions()): RuntimeTransports {
    val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = options.connectTimeout.inWholeMilliseconds
            requestTimeoutMillis = options.requestTimeoutBackstop.inWholeMilliseconds
        }
        install(WebSockets) {
            pingIntervalMillis = options.pingInterval.inWholeMilliseconds
        }
    }
    return RuntimeTransports(KtorRuntimeHttp(client, endpoint), KtorWebSocketConnector(client), client::close)
}
