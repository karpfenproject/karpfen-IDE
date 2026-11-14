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
package io.karpfen.ide.runtime.protocol.golden

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Covers [RuntimeWireWriter], the port of the runtime's writers. The tests cover the push examples of the runtime's
 * wire format byte for byte, and the object JSON with its number, escape and relation rules. They cover the refusal of
 * reference loops with the runtime's text, the snapshot body, and agreement with the client's reader and quoting.
 */
class RuntimeWireWriterTest {

    private val environment = "env-1761560123456"
    private val client = "karpfen-ide-4f2c9a1b7e03"

    /** The `turtlePosition` vector of the single robot example after a change. */
    private val turtlePosition = fakeObject("turtlePosition", "Vector") {
        number("x", 5.0)
        number("y", 5.3)
    }

    @Test
    fun `an objectChanged push is written as the runtime writes it`() {
        val value = RuntimeWireWriter.objectJson(turtlePosition)
        val frame = RuntimeWireWriter.envelope(environment, client, "objectChanged", RuntimeWireWriter.objectChangedPayload("turtlePosition", 58, value))

        assertEquals(
            """{"environmentKey":"env-1761560123456","clientId":"karpfen-ide-4f2c9a1b7e03","messageType":"objectChanged",""" +
                """"payload":{"objectId":"turtlePosition","revision":58,"value":{"__id__": "turtlePosition", "__type__": "Vector", "x": 5, "y": 5.3}}}""",
            frame,
        )
    }

    @Test
    fun `objectDeleted and stateChange pushes are written as the runtime writes them`() {
        assertEquals(
            """{"environmentKey":"env-1761560123456","clientId":"karpfen-ide-4f2c9a1b7e03","messageType":"objectDeleted",""" +
                """"payload":{"objectId":"gen_Drone_4","revision":59}}""",
            RuntimeWireWriter.envelope(environment, client, "objectDeleted", RuntimeWireWriter.objectDeletedPayload("gen_Drone_4", 59)),
        )
        assertEquals(
            """{"environmentKey":"env-1761560123456","clientId":"karpfen-ide-4f2c9a1b7e03","messageType":"stateChange",""" +
                """"payload":{"stack":["observe"],"modelElementId":"turtle","revision":60}}""",
            RuntimeWireWriter.envelope(environment, client, "stateChange", RuntimeWireWriter.stateChangePayload("turtle", listOf("observe"), 60)),
        )
    }

    @Test
    fun `a domainEvent push carries the published event in org json's member order`() {
        val payload = RuntimeWireWriter.domainEventPayload("public", "start", "", "none", "EXTERNAL_MESSAGE", 1761560130123, 3000)
        val frame = RuntimeWireWriter.envelope(environment, client, "domainEvent", payload)

        assertEquals(
            """{"environmentKey":"env-1761560123456","clientId":"karpfen-ide-4f2c9a1b7e03","messageType":"domainEvent",""" +
                """"payload":{"domain":"public","payload":{"payload":"","source":"EXTERNAL_MESSAGE","ttlMs":3000,"event":"start",""" +
                """"payloadFormat":"none","timestamp":1761560130123}}}""",
            frame,
        )
        val documented = """{"event":"start","payload":"","payloadFormat":"none","source":"EXTERNAL_MESSAGE","timestamp":1761560130123,"ttlMs":3000}"""
        val published = assertIs<WireValue.WireObject>(read(payload)["payload"])
        assertEquals(read(documented).fields.sortedBy { it.name }, published.fields.sortedBy { it.name }, "the members of the documented example")
    }

    @Test
    fun `numbers are written without fraction when integral and as the JVM writes doubles otherwise`() {
        assertEquals(
            listOf("5", "0", "0", "-3", "1000000000000000000", "0.2", "5.3", "1.0E20", "-2.5E-7", "NaN", "Infinity", "-Infinity"),
            listOf(5.0, 0.0, -0.0, -3.0, 1.0E18, 0.2, 5.3, 1.0E20, -2.5E-7, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
                .map(RuntimeWireWriter::number),
        )
        val sensor = fakeObject("sensor", "Sensor") {
            number("nan", Double.NaN)
            numbers("readings", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0E20, 2.0)
        }
        assertEquals(
            """{"__id__": "sensor", "__type__": "Sensor", "nan": NaN, "readings": [Infinity, -Infinity, 1.0E20, 2]}""",
            RuntimeWireWriter.objectJson(sensor),
        )
    }

    @Test
    fun `strings, ids, type names and keys are escaped by the runtime writer's rule`() {
        val note = fakeObject("note \"1\"", "Text\\Note") {
            text("body", "line one\nback\\slash \"quoted\" \u0001 tab\t – é")
            text("key\nwith break", "")
            flag("pinned", true)
            flags("seen", false, true)
            texts("tags", "a", "b")
        }

        assertEquals(
            """{"__id__": "note \"1\"", "__type__": "Text\\Note", "body": "line one\nback\\slash \"quoted\" \u0001 tab\t – é", """ +
                """"key\nwith break": "", "pinned": true, "seen": [false, true], "tags": ["a", "b"]}""",
            RuntimeWireWriter.objectJson(note),
        )
    }

    @Test
    fun `relations are written as nested objects, empty ones and unresolved entries as null`() {
        val vector = fakeObject("", "Vector") { number("x", 1.0) }
        val robot = fakeObject("robot", "Robot") {
            has("direction", vector)
            has("battery", null)
            hasMany("wheels", fakeObject("w1", "Wheel"), null)
            knows("target", "ghost")
        }

        assertEquals(
            """{"__id__": "robot", "__type__": "Robot", "direction": {"__id__": "", "__type__": "Vector", "x": 1}, "battery": null, """ +
                """"wheels": [{"__id__": "w1", "__type__": "Wheel"}, null], "target": null}""",
            RuntimeWireWriter.objectJson(robot),
        )
    }

    @Test
    fun `a known object is written in full where it is referred to, twice when it is referred to twice`() {
        val wallSwitch = fakeObject("wallSwitch", "Switch") { text("position", "down") }
        val lamp = fakeObject("lamp", "Lamp") {
            text("mode", "off")
            has("bulb", fakeObject("bulb", "Bulb") {
                number("watts", 40.0)
                flag("lit", false)
            })
            knows("control", "wallSwitch")
            knowsMany("backups", "wallSwitch", "wallSwitch")
        }
        val model = mapOf("wallSwitch" to wallSwitch)

        assertEquals(
            """{"__id__": "lamp", "__type__": "Lamp", "mode": "off", "bulb": {"__id__": "bulb", "__type__": "Bulb", "watts": 40, "lit": false}, """ +
                """"control": {"__id__": "wallSwitch", "__type__": "Switch", "position": "down"}, "backups": [""" +
                """{"__id__": "wallSwitch", "__type__": "Switch", "position": "down"}, {"__id__": "wallSwitch", "__type__": "Switch", "position": "down"}]}""",
            RuntimeWireWriter.objectJson(lamp, model::get),
        )
    }

    @Test
    fun `an object reachable from itself is refused with the runtime's text naming the object met again`() {
        val a = fakeObject("a", "Node") {
            number("visits", 0.0)
            knows("next", "b")
        }
        val b = fakeObject("b", "Node") {
            number("visits", 0.0)
            knows("next", "a")
        }
        val ring = fakeObject("ring", "Ring") { hasMany("nodes", a, b) }
        val copies = mapOf("a" to a.copy(), "b" to b.copy())
        val model = mapOf("a" to a, "b" to b)

        val failure = assertFailsWith<IllegalStateException> { RuntimeWireWriter.objectJson(ring, model::get) }
        assertEquals("Reference loop through object 'a' cannot be written as JSON", failure.message)
        assertEquals(RuntimeText.REFERENCE_LOOP.render(mapOf("id" to "a")), failure.message)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { RuntimeWireWriter.objectJson(ring, copies::get) }.message)
        val selfKnowing = fakeObject("self", "Node") { knows("next", "self") }
        assertEquals(
            RuntimeWireWriter.referenceLoopMessage("self"),
            assertFailsWith<IllegalStateException> { RuntimeWireWriter.objectJson(selfKnowing, mapOf("self" to selfKnowing)::get) }.message,
        )
    }

    @Test
    fun `the snapshot body has its fixed member order and splices the roots in`() {
        val root = RuntimeWireWriter.objectJson(fakeObject("turtle", "Robot") { number("d_closest_obstacle", 0.2) })

        assertEquals(
            """{"environmentKey":"env-1761560123456","phase":"running","revision":57,"activeStates":{"turtle":["observe","react to obstacle"]},""" +
                """"stateMachines":{},"roots":[{"__id__": "turtle", "__type__": "Robot", "d_closest_obstacle": 0.2}]}""",
            RuntimeWireWriter.snapshot(environment, RuntimePhase.RUNNING, 57, mapOf("turtle" to listOf("observe", "react to obstacle")), listOf(root)),
        )
        assertEquals(
            """{"environmentKey":"env-1","phase":"activated","revision":0,"activeStates":{},"stateMachines":{},"roots":[{"a":1},{"b":2}]}""",
            RuntimeWireWriter.snapshot("env-1", RuntimePhase.ACTIVATED, 0, emptyMap(), listOf("""{"a":1}""", """{"b":2}""")),
        )
        assertEquals(
            """{"environmentKey":"env-1","phase":"running","revision":4,"activeStates":{},""" +
                """"stateMachines":{"living room":null,"gen_Bulb_0":"blinking"},"roots":[]}""",
            RuntimeWireWriter.snapshot("env-1", RuntimePhase.RUNNING, 4, emptyMap(), emptyList(), linkedMapOf("living room" to null, "gen_Bulb_0" to "blinking")),
        )
    }

    @Test
    fun `the client's quoting and reader agree with the port`() {
        val texts = listOf("", "living room", "100 W – full power", "\"\\\b\u000C\n\r\t/", (0..0xFF).map(Int::toChar).joinToString(""), "  😀")
        texts.forEach { text -> assertEquals(RuntimeWireWriter.quote(text), RuntimeJson.quote(text), text) }

        val frames = listOf(
            RuntimeWireWriter.objectJson(fakeObject("odd \u0001 id", "T\"ype") { text("k\\ey", "v\nalue") }),
            RuntimeWireWriter.stateChangePayload("living room", listOf("on", "full power"), 3),
            RuntimeWireWriter.domainEventPayload("switch", "press", "{\"x\": 1}", "json", "EXTERNAL_MESSAGE", 1, 30_000),
            RuntimeWireWriter.snapshot("env-1", RuntimePhase.STOPPED, 9, mapOf("lamp" to listOf("broken")), listOf(RuntimeWireWriter.objectJson(turtlePosition))),
        )
        frames.forEach { frame -> assertIs<JsonReading.Read>(RuntimeJson.read(frame), frame) }
        assertEquals(
            WireValue.WireString("v\nalue"),
            read(frames.first())["k\\ey"],
        )
    }

    /** The JSON object [text], failing the test if it is none. */
    private fun read(text: String): WireValue.WireObject =
        assertIs<WireValue.WireObject>(assertIs<JsonReading.Read>(RuntimeJson.read(text), text).value)
}
