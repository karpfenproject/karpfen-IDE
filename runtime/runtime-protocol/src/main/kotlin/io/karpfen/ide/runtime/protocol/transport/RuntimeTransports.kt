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
package io.karpfen.ide.runtime.protocol.transport

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two seams through which the client reaches a runtime, and what they hold. In production they hold the Ktor
 * adapter's HTTP client. For a fake they hold nothing.
 *
 * @property http the HTTP seam.
 * @property webSockets the WebSocket seam.
 * @param release frees what the transports hold. [close] calls it once.
 */
public class RuntimeTransports(
    public val http: RuntimeHttp,
    public val webSockets: WebSocketConnector,
    private val release: () -> Unit = {},
) : AutoCloseable {

    /** Whether [close] ran. */
    private val closed = AtomicBoolean(false)

    /** Frees what the transports hold. Calling it again does nothing, so [release] runs once. */
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
