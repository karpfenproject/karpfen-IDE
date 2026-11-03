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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the keys of the wire: [EnvironmentKey], [ClientId] and [AccessKey] accept what the runtime issues and refuse
 * what would break the handshake or the envelope, and [ClientId.random] draws the IDE's client ids from the given
 * random source.
 */
class KeysTest {

    @Test
    fun `an environment key is not blank and holds no colon, quote, backslash or control character`() {
        assertEquals("env-1761560123456", EnvironmentKey("env-1761560123456").value)
        assertEquals("env-1", EnvironmentKey("env-1").toString())
        listOf("", "  ", "env:1", "env\"1", "env\\1", "env\n1", "env\u00071").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { EnvironmentKey(value) }
        }
    }

    @Test
    fun `a client id is one to 64 letters, digits, underscores and hyphens`() {
        listOf("ide-4f2c", "karpfen-ide-4f2c9a1b7e03", "a", "A_b-9", "x".repeat(64)).forEach { assertEquals(it, ClientId(it).value) }
        listOf("", "x".repeat(65), "ide:4f2c", "ide 4f2c", "idé", "ide.4f2c", "ide/4f2c").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { ClientId(value) }
        }
    }

    @Test
    fun `an access key is not blank and holds no colon or whitespace`() {
        assertEquals("ak-ide-4f2c-1761555601234-4821", AccessKey("ak-ide-4f2c-1761555601234-4821").value)
        listOf("", " ", "ak:1", "ak 1", "ak-1\n", "ak-1\t").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { AccessKey(value) }
        }
    }

    @Test
    fun `a random client id is the IDE prefix and twelve hexadecimal digits from the given source`() {
        val first = ClientId.random(Random(42))
        val again = ClientId.random(Random(42))
        val other = ClientId.random(Random(43))

        assertEquals(first, again, "a seeded source gives the same id")
        assertNotEquals(first, other)
        assertTrue(Regex("karpfen-ide-[0-9a-f]{12}").matches(first.value), first.value)
        assertTrue(Regex("karpfen-ide-[0-9a-f]{12}").matches(other.value), other.value)
    }
}
