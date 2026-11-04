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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the production transports: the options' defaults and their refusal of durations that are not positive, and
 * transports that are made without opening a connection and closed as often as wanted.
 */
class KtorTransportsTest {

    @Test
    fun `the options default to a five second connect timeout, a two minute backstop and a twenty second ping`() {
        val options = KtorTransportOptions()

        assertEquals(5.seconds, options.connectTimeout)
        assertEquals(120.seconds, options.requestTimeoutBackstop)
        assertEquals(20.seconds, options.pingInterval)
    }

    @Test
    fun `the options refuse durations that are not positive`() {
        assertFailsWith<IllegalArgumentException> { KtorTransportOptions(connectTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { KtorTransportOptions(requestTimeoutBackstop = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { KtorTransportOptions(pingInterval = Duration.ZERO) }
    }

    @Test
    fun `the transports are made without a connection and closed as often as wanted`() {
        val transports = ktorTransports(RuntimeEndpoint.parse("http://127.0.0.1:1"))

        assertIs<KtorRuntimeHttp>(transports.http)
        assertIs<KtorWebSocketConnector>(transports.webSockets)
        transports.close()
        transports.close()
    }
}
