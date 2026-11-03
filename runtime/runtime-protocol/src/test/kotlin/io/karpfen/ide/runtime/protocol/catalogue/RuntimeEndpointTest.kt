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
package io.karpfen.ide.runtime.protocol.catalogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers [RuntimeEndpoint]: parsing a runtime URL with or without a port and a trailing slash, the default ports, IPv6
 * hosts, the refusal of everything else, and the HTTP base and WebSocket URL derived from an endpoint.
 */
class RuntimeEndpointTest {

    @Test
    fun `a URL with host and port gives the HTTP base without a trailing slash and the WebSocket URL`() {
        val endpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

        assertEquals(RuntimeEndpoint(secure = false, host = "127.0.0.1", port = 8080), endpoint)
        assertEquals("http://127.0.0.1:8080", endpoint.httpBase)
        assertEquals("ws://127.0.0.1:8080/ws", endpoint.webSocketUrl)
        assertEquals(endpoint, RuntimeEndpoint.parse("http://127.0.0.1:8080/"))
    }

    @Test
    fun `https gives a secure endpoint with wss and the default ports apply without a port`() {
        val secure = RuntimeEndpoint.parse("https://runtime.example.org/")

        assertEquals(RuntimeEndpoint(secure = true, host = "runtime.example.org", port = 443), secure)
        assertEquals("https://runtime.example.org:443", secure.httpBase)
        assertEquals("wss://runtime.example.org:443/ws", secure.webSocketUrl)
        assertEquals(80, RuntimeEndpoint.parse("http://localhost").port)
        assertEquals(RuntimeEndpoint(secure = false, host = "localhost", port = 49153), RuntimeEndpoint.parse("HTTP://localhost:49153"))
    }

    @Test
    fun `an IPv6 host keeps its brackets in the URLs only`() {
        val endpoint = RuntimeEndpoint.parse("http://[::1]:8080")

        assertEquals("::1", endpoint.host)
        assertEquals("http://[::1]:8080", endpoint.httpBase)
        assertEquals("ws://[::1]:8080/ws", endpoint.webSocketUrl)
    }

    @Test
    fun `anything but http or https with a host, an optional port and at most a slash is refused`() {
        listOf(
            "", "127.0.0.1:8080", "ws://127.0.0.1:8080", "ftp://host", "http://", "http://:8080", "http://host:",
            "http://host:0", "http://host:65536", "http://host:80a", "http://host/ws", "http://host:8080/path",
            "http://host?x=1", "http://host:8080/?x=1", "http://user@host", "http://host name", "http://[::1",
            "http://host:8080#top",
        ).forEach { url -> assertFailsWith<IllegalArgumentException>(url) { RuntimeEndpoint.parse(url) } }
    }

    @Test
    fun `an endpoint needs a host and a valid port`() {
        assertFailsWith<IllegalArgumentException> { RuntimeEndpoint(secure = false, host = " ", port = 8080) }
        assertFailsWith<IllegalArgumentException> { RuntimeEndpoint(secure = false, host = "localhost", port = 0) }
    }
}
