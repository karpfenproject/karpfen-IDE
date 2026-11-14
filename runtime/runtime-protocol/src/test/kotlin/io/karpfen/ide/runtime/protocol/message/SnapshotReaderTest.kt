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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.golden.fakeObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the snapshot reader and the compact writer. A snapshot as the runtime writes it is read with its phase,
 * revision, stacks in order and roots. Every departure from that shape is an unexpected body with an excerpt. Values
 * written again compactly read back to themselves, with numbers written as the runtime writes its doubles.
 */
class SnapshotReaderTest {

    /** A lamp with a nested switch. */
    private val lamp = fakeObject("lamp", "Lamp") {
        flag("on", false)
        number("brightness", 0.5)
        has("switch", fakeObject("lamp switch", "Switch") { text("label", "living room") })
    }

    /** What the reader makes of [body]. */
    private fun read(body: String): SnapshotReading = SnapshotReader.read(body)

    @Test
    fun `a snapshot as the runtime writes it is read by member name`() {
        val body = RuntimeWireWriter.snapshot(
            "env-1761555600000",
            RuntimePhase.RUNNING,
            57,
            linkedMapOf("turtle" to listOf("observe", "react to obstacle"), "lamp" to listOf("off")),
            listOf(RuntimeWireWriter.objectJson(lamp)),
            linkedMapOf("turtle" to null, "lamp" to "blinking"),
        )

        val snapshot = assertIs<SnapshotReading.Read>(read(body)).snapshot

        assertEquals(EnvironmentKey("env-1761555600000"), snapshot.environment)
        assertEquals(RuntimePhase.RUNNING, snapshot.phase)
        assertEquals(57, snapshot.revision)
        assertEquals(listOf("turtle", "lamp"), snapshot.activeStates.keys.toList())
        assertEquals(listOf("observe", "react to obstacle"), snapshot.activeStates["turtle"])
        assertEquals(linkedMapOf("turtle" to null, "lamp" to "blinking"), snapshot.stateMachines)
        assertEquals(listOf("turtle", "lamp"), snapshot.stateMachines.keys.toList())
        val root = snapshot.roots.single()
        assertEquals(WireValue.WireString("lamp"), root["__id__"])
        assertEquals(WireValue.WireNumber(0.5), root["brightness"])
    }

    @Test
    fun `a snapshot before the first tick has no stacks and revision zero, in any member order`() {
        val body = """{"roots":[],"stateMachines":{},"activeStates":{},"revision":0,"phase":"configuring","environmentKey":"env-1"}"""

        val snapshot = assertIs<SnapshotReading.Read>(read(body)).snapshot

        assertEquals(RuntimeModelSnapshot(EnvironmentKey("env-1"), RuntimePhase.CONFIGURING, 0, emptyMap(), emptyList()), snapshot)
    }

    @Test
    fun `every departure from the snapshot's shape is an unexpected body`() {
        val valid = mapOf(
            "environmentKey" to "\"env-1\"",
            "phase" to "\"running\"",
            "revision" to "3",
            "activeStates" to """{"lamp":["on"]}""",
            "stateMachines" to """{"lamp":null,"bulb":"glowing"}""",
            "roots" to """[{"__id__": "lamp", "__type__": "Lamp"}]""",
        )
        fun body(change: Pair<String, String?>): String =
            (valid + change).filterValues { it != null }.entries.joinToString(",", "{", "}") { (name, value) -> "\"$name\":$value" }

        listOf(
            "environmentKey" to null,
            "environmentKey" to "\"env:1\"",
            "environmentKey" to "17",
            "phase" to "\"paused\"",
            "phase" to null,
            "revision" to "-1",
            "revision" to "2.5",
            "revision" to "\"3\"",
            "activeStates" to """{"lamp":"on"}""",
            "activeStates" to """{"lamp":[1]}""",
            "activeStates" to "[]",
            "stateMachines" to null,
            "stateMachines" to """{"lamp":1}""",
            "stateMachines" to """{"lamp":["glowing"]}""",
            "roots" to """[{"__id__": "lamp"}]""",
            "roots" to """["lamp"]""",
            "roots" to null,
        ).forEach { change ->
            val text = body(change)
            val violation = assertIs<SnapshotReading.Unexpected>(read(text), text).violation
            assertEquals(ViolationKind.UNEXPECTED_BODY, violation.kind)
            assertTrue(change.first in violation.detail, violation.detail)
            assertEquals(text.take(200), violation.excerpt)
        }
    }

    @Test
    fun `a body that is not a JSON object is unexpected`() {
        listOf("", "[]", "{\"phase\":\"running\"} trailing", "Unexpected error").forEach { body ->
            assertEquals(ViolationKind.UNEXPECTED_BODY, assertIs<SnapshotReading.Unexpected>(read(body), body).violation.kind)
        }
    }

    @Test
    fun `values written compactly read back to themselves and numbers follow the runtime's rule`() {
        val text = """{ "a" : [1.0, 0.2, 1.0E20, NaN, Infinity, -Infinity, 1761560130123], "b": {"c": "x\"y\n", "c": null}, "d": [true, false] }"""
        val value = assertIs<JsonReading.Read>(RuntimeJson.read(text)).value

        val compact = CompactJson.write(value)

        assertEquals(
            """{"a":[1,0.2,1.0E20,NaN,Infinity,-Infinity,1761560130123],"b":{"c":"x\"y\n","c":null},"d":[true,false]}""",
            compact,
        )
        assertEquals(value, assertIs<JsonReading.Read>(RuntimeJson.read(compact)).value)
        assertEquals("0", CompactJson.write(WireValue.WireNumber(-0.0)))
    }
}
