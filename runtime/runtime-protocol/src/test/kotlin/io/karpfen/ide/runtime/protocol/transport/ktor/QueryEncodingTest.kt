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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [QueryEncoding]. The unreserved characters stay, a space is `%20` and a `+` is `%2B`. The characters that
 * structure a query are escaped. Every byte of a non-ASCII character's UTF-8 encoding becomes `%XX` in upper case.
 */
class QueryEncodingTest {

    @Test
    fun `the unreserved characters stay as they are`() {
        val unreserved = "ABCXYZabcxyz0189-._~"

        assertEquals(unreserved, QueryEncoding.encode(unreserved))
        assertEquals("env-1761560123456", QueryEncoding.encode("env-1761560123456"))
        assertEquals("karpfen-ide-4f2c9a1b7e03", QueryEncoding.encode("karpfen-ide-4f2c9a1b7e03"))
    }

    @Test
    fun `object keys and state names with spaces are encoded with percent twenty`() {
        assertEquals("APB%202101", QueryEncoding.encode("APB 2101"))
        assertEquals("living%20room", QueryEncoding.encode("living room"))
        assertEquals("full%20power", QueryEncoding.encode("full power"))
        assertEquals("react%20to%20obstacle", QueryEncoding.encode("react to obstacle"))
    }

    @Test
    fun `the characters that structure a query are escaped and a plus never means a space`() {
        assertEquals("a%26b%3Dc%25d%2Be", QueryEncoding.encode("a&b=c%d+e"))
        assertEquals("%2A%3A%2F%3F%23%22%5C", QueryEncoding.encode("*:/?#\"\\"))
        assertEquals("", QueryEncoding.encode(""))
    }

    @Test
    fun `every byte of a non-ASCII character is encoded in upper-case hexadecimal`() {
        assertEquals("%C3%BC", QueryEncoding.encode("ü"))
        assertEquals("on%20%E2%80%93%20off", QueryEncoding.encode("on – off"))
        assertEquals("%F0%9F%90%9F", QueryEncoding.encode("🐟"))
        assertEquals("%09%0A", QueryEncoding.encode("\t\n"))
    }
}
