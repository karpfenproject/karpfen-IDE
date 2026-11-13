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
import kotlin.test.assertNull

/**
 * Covers [RuntimeText]. Every rejection text of the runtime renders from its placeholder values and matches back to
 * them. A body matches only as a whole. The texts are disjoint, and that includes the two texts about a missing
 * execution thread. An empty environment key matches, and the placeholders match what the runtime can put there.
 */
class RuntimeTextTest {

    @Test
    fun `every template renders and matches back to its placeholder values`() {
        RuntimeText.entries.forEach { text ->
            val values = samplesFor(text)
            val rendered = text.render(values)
            assertEquals(values, text.match(rendered), "${text.name}: $rendered")
        }
    }

    @Test
    fun `the templates are the runtime's texts`() {
        assertEquals(
            "Environment with key env-1761560123456 has state machines attached to objects missing from its model: ghost",
            RuntimeText.UNKNOWN_ATTACHMENT.render(mapOf("env" to "env-1761560123456", "keys" to "ghost")),
        )
        assertEquals(
            "Environment with key 'env-1' has no execution thread",
            RuntimeText.NOT_ACTIVATED.render(mapOf("env" to "env-1")),
        )
        assertEquals(
            "Client karpfen-ide-4f2c9a1b7e03 has no open WebSocket session for environment env-1",
            RuntimeText.NO_SESSION.render(mapOf("client" to "karpfen-ide-4f2c9a1b7e03", "env" to "env-1")),
        )
        assertEquals(
            "Environment with key env-1 did not reach a tick boundary within 10000 ms",
            RuntimeText.TICK_BOUNDARY_TIMEOUT.render(mapOf("env" to "env-1", "ms" to "10000")),
        )
        assertEquals(RejectionKind.entries - RejectionKind.CONTENT_REJECTED - RejectionKind.UNCLASSIFIED, RuntimeText.entries.map { it.kind })
    }

    @Test
    fun `every rendered text matches exactly one template`() {
        RuntimeText.entries.forEach { text ->
            val rendered = text.render(samplesFor(text))
            assertEquals(listOf(text), RuntimeText.entries.filter { it.match(rendered) != null }, rendered)
        }
    }

    @Test
    fun `the quoted and the unquoted texts about a missing execution thread are told apart`() {
        val quoted = "Environment with key 'env-7' has no execution thread"
        val plain = "Environment with key env-7 has no execution thread"

        assertEquals(mapOf("env" to "env-7"), RuntimeText.NOT_ACTIVATED.match(quoted))
        assertNull(RuntimeText.NO_EXECUTION_THREAD.match(quoted))
        assertEquals(mapOf("env" to "env-7"), RuntimeText.NO_EXECUTION_THREAD.match(plain))
        assertNull(RuntimeText.NOT_ACTIVATED.match(plain))
    }

    @Test
    fun `an empty environment key matches, as the runtime renders a present but empty parameter`() {
        assertEquals(mapOf("env" to ""), RuntimeText.UNKNOWN_ENVIRONMENT.match("Environment with key  does not exist"))
        assertEquals(mapOf("env" to ""), RuntimeText.NOT_ACTIVATED.match("Environment with key '' has no execution thread"))
        assertEquals(mapOf("client" to "", "env" to ""), RuntimeText.NO_SESSION.match("Client  has no open WebSocket session for environment "))
    }

    @Test
    fun `a body matches only as a whole`() {
        val text = "Environment with key env-1 does not exist"
        assertNull(RuntimeText.UNKNOWN_ENVIRONMENT.match("$text."))
        assertNull(RuntimeText.UNKNOWN_ENVIRONMENT.match(" $text"))
        assertNull(RuntimeText.UNEXPECTED_ERROR.match("Unexpected error: boom"))
        assertEquals(emptyMap(), RuntimeText.UNEXPECTED_ERROR.match("Unexpected error"))
    }

    @Test
    fun `placeholders match what the runtime can put there and nothing else`() {
        assertNull(RuntimeText.UNKNOWN_ENVIRONMENT.match("Environment with key env 1 does not exist"), "a key has no whitespace")
        assertNull(RuntimeText.TICK_BOUNDARY_TIMEOUT.match("Environment with key env-1 did not reach a tick boundary within ten ms"))
        assertEquals(
            mapOf("state" to "react to obstacle", "element" to "living room"),
            RuntimeText.UNKNOWN_STATE.match("Unknown state 'react to obstacle' for model element 'living room'"),
        )
        assertEquals(
            mapOf("env" to "env-1", "keys" to "ghost, phantom"),
            RuntimeText.UNKNOWN_ATTACHMENT.match("Environment with key env-1 has state machines attached to objects missing from its model: ghost, phantom"),
        )
        assertEquals(
            mapOf("name" to "envKey"),
            RuntimeText.MISSING_PARAMETER.match("Missing required parameter: envKey"),
        )
    }

    @Test
    fun `rendering without a value for a placeholder is refused`() {
        assertFailsWith<IllegalArgumentException> { RuntimeText.NO_SESSION.render(mapOf("env" to "env-1")) }
    }

    /** Sample values for every placeholder of [text], with spaces and quotes where the runtime allows them. */
    private fun samplesFor(text: RuntimeText): Map<String, String> =
        PLACEHOLDER.findAll(text.template).map { it.groupValues[1] }.associateWith { SAMPLES.getValue(it) }

    private companion object {
        /** A placeholder of a template. */
        val PLACEHOLDER = Regex("""\{(\w+)}""")

        /** A sample value per placeholder. */
        val SAMPLES = mapOf(
            "env" to "env-1761560123456",
            "client" to "karpfen-ide-4f2c9a1b7e03",
            "name" to "envKey",
            "element" to "living room",
            "state" to "full power",
            "keys" to "ghost, phantom",
            "id" to "a",
            "ms" to "10000",
            "feature" to "TickLimit",
        )
    }
}
