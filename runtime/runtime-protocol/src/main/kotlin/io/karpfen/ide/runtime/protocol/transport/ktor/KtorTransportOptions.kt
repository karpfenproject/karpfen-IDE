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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the Ktor transports talk to the runtime, with the production defaults. The client's own timeouts per route run
 * above the transport. The transport's limits only bound what the client's timeouts cannot see.
 *
 * @property connectTimeout how long connecting to the runtime may take before the call is unreachable.
 * @property requestTimeoutBackstop the longest any HTTP exchange may take. It lies above every timeout of the client,
 *   so it only ends exchanges that the client's own timeouts somehow missed.
 * @property pingInterval how often the client pings an open WebSocket. A dead runtime is then noticed without waiting
 *   for the runtime's own 60-second ping.
 * @throws IllegalArgumentException if a duration is not positive.
 */
public data class KtorTransportOptions(
    public val connectTimeout: Duration = 5.seconds,
    public val requestTimeoutBackstop: Duration = 120.seconds,
    public val pingInterval: Duration = 20.seconds,
) {
    init {
        require(listOf(connectTimeout, requestTimeoutBackstop, pingInterval).all { it.isPositive() }) {
            "every duration of the transports must be positive: $this"
        }
    }
}
