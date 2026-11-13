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

import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.golden.fakeObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [RuntimeFrameDecoder]. The runtime's push examples decode to their pushes. The members org.json writes are
 * read in any order, and a revision is required. An engine status decodes with and without a halt, and a payload that
 * is no status is malformed. An id mismatch is reported alongside the push applied under the
 * value's id. Unknown types are unrecognized rather than violations. Every malformed, foreign or undecodable frame
 * becomes a violation with an excerpt and whatever could be salvaged.
 */
class RuntimeFrameDecoderTest {

    /** The environment of the connection. */
    private val environment = "env-1761560123456"
    /** The client id of the connection. */
    private val client = "karpfen-ide-4f2c9a1b7e03"
    /** The decoder of the connection. */
    private val decoder = RuntimeFrameDecoder(EnvironmentKey(environment), ClientId(client))

    /** A push of [messageType] with [payload] to the connection. */
    private fun frame(messageType: String, payload: String): String = RuntimeWireWriter.envelope(environment, client, messageType, payload)

    /** The push [text] decodes to, failing the test if it is a violation. */
    private fun pushOf(text: String): DecodedFrame.Push = assertIs<DecodedFrame.Push>(decoder.decode(text))

    /** The violation [text] decodes to, failing the test if it is a push. */
    private fun violationOf(text: String): ProtocolViolation = assertIs<DecodedFrame.Violation>(decoder.decode(text)).violation

    @Test
    fun `the runtime's objectChanged example decodes to the changed object at its revision`() {
        val decoded = pushOf(
            """{"environmentKey":"env-1761560123456","clientId":"karpfen-ide-4f2c9a1b7e03","messageType":"objectChanged",""" +
                """"payload":{"objectId":"turtlePosition","revision":58,"value":{"__id__": "turtlePosition", "__type__": "Vector", "x": 5, "y": 5.3}}}""",
        )

        val push = assertIs<RuntimePush.ObjectChanged>(decoded.push)
        assertEquals("turtlePosition", push.objectId)
        assertEquals(58, push.revision)
        assertEquals(WireValue.WireNumber(5.3), push.value["y"])
        assertEquals(emptyList(), decoded.violations)
    }

    @Test
    fun `the runtime's objectDeleted, stateChange and domainEvent examples decode`() {
        assertEquals(
            RuntimePush.ObjectDeleted("gen_Drone_4", 59),
            pushOf(frame("objectDeleted", """{"objectId":"gen_Drone_4","revision":59}""")).push,
        )
        assertEquals(
            RuntimePush.StateChanged("turtle", listOf("observe"), 60),
            pushOf(frame("stateChange", """{"stack":["observe"],"modelElementId":"turtle","revision":60}""")).push,
        )
        val inner = """{"event":"start","payload":"","payloadFormat":"none","source":"EXTERNAL_MESSAGE","timestamp":1761560130123,"ttlMs":3000}"""
        assertEquals(
            RuntimePush.DomainEvent(
                "public",
                PublishedEvent("start", "", "none", "EXTERNAL_MESSAGE", 1_761_560_130_123, 3000),
                inner,
            ),
            pushOf(frame("domainEvent", """{"domain":"public","payload":$inner}""")).push,
        )
    }

    @Test
    fun `stateChange and domainEvent members are read in the order org json writes them`() {
        val state = RuntimeWireWriter.stateChangePayload("lamp", listOf("on", "bright"), 7)
        assertEquals(RuntimePush.StateChanged("lamp", listOf("on", "bright"), 7), pushOf(frame("stateChange", state)).push)

        val event = RuntimeWireWriter.domainEventPayload("control", "toggle", "{\"x\":1}", "json", "INTERNAL", 1_761_560_130_123, 0)
        val push = assertIs<RuntimePush.DomainEvent>(pushOf(frame("domainEvent", event)).push)
        assertEquals(PublishedEvent("toggle", "{\"x\":1}", "json", "INTERNAL", 1_761_560_130_123, 0), push.event)
        assertEquals("control", push.domain)
    }

    @Test
    fun `an engineStatus push decodes to the status, without a halt and with a halt at a state or a transition`() {
        val running = RuntimeWireWriter.engineStatusPayload(paused = false, waiting = false)
        val betweenTicks = RuntimeWireWriter.engineStatusPayload(paused = true, waiting = true)
        val atState = RuntimeWireWriter.engineStatusPayload(true, true, RuntimeWireWriter.stateHalt("crossing", "exit", "cars wait"))
        val atTransition = RuntimeWireWriter.engineStatusPayload(
            true,
            true,
            RuntimeWireWriter.transitionHalt("crossing", "end", "SplitTransition(fork)", "SPLIT", listOf("idle"), listOf("a", "b"), 2),
        )

        assertEquals(
            listOf(
                EngineStatus(paused = false, waiting = false, halt = null),
                EngineStatus(paused = true, waiting = true, halt = null),
                EngineStatus(true, true, EngineHalt.AtState("crossing", "cars wait", StateTiming.EXIT)),
                EngineStatus(
                    true,
                    true,
                    EngineHalt.AtTransition("crossing", HaltedTransitionKind.SPLIT, listOf("idle"), listOf("a", "b"), 2, TransitionTiming.END),
                ),
            ).map { RuntimePush.EngineStatusChanged(it) },
            listOf(running, betweenTicks, atState, atTransition).map { pushOf(frame("engineStatus", it)) }.onEach {
                assertEquals(emptyList(), it.violations)
            }.map { it.push },
        )
    }

    @Test
    fun `an engineStatus payload that is not a status is malformed`() {
        listOf(
            "[]",
            "\"paused\"",
            """{"paused":true}""",
            """{"paused":"yes","waiting":true}""",
            """{"paused":true,"waiting":true,"halt":"cars wait"}""",
            """{"paused":true,"waiting":true,"halt":{"modelElementId":"crossing","timing":"start","state":"cars wait"}}""",
            """{"paused":true,"waiting":true,"halt":{"timing":"entry","state":"cars wait"}}""",
        ).forEach { payload ->
            val violation = violationOf(frame("engineStatus", payload))
            assertEquals(ViolationKind.MALFORMED_PAYLOAD, violation.kind, payload)
            assertEquals("engineStatus", violation.messageType, payload)
        }
    }

    @Test
    fun `an object push carries the whole value with the runtime's edge cases`() {
        val value = RuntimeWireWriter.objectJson(
            fakeObject("APB 2101", "Sensor") {
                text("label", "line\nback\\slash \"quoted\" \u0001")
                number("nan", Double.NaN)
                number("big", 1.0E20)
                number("low", Double.NEGATIVE_INFINITY)
                has("anchor", null)
                hasMany("parts", fakeObject("", "Part"), null)
            },
        )

        val push = assertIs<RuntimePush.ObjectChanged>(pushOf(frame("objectChanged", RuntimeWireWriter.objectChangedPayload("APB 2101", 3, value))).push)

        assertEquals(WireValue.WireString("line\nback\\slash \"quoted\" \u0001"), push.value["label"])
        assertEquals(WireValue.WireNumber(Double.NaN), push.value["nan"])
        assertEquals(WireValue.WireNumber(1.0E20), push.value["big"])
        assertEquals(WireValue.WireNumber(Double.NEGATIVE_INFINITY), push.value["low"])
        assertEquals(WireValue.WireNull, push.value["anchor"])
        val parts = assertIs<WireValue.WireArray>(push.value["parts"]).items
        assertEquals(WireValue.WireString(""), (parts[0] as WireValue.WireObject)["__id__"])
        assertEquals(WireValue.WireNull, parts[1])
    }

    @Test
    fun `a push without a revision is malformed, since the client needs the runtime with IDE support`() {
        listOf(
            frame("objectChanged", """{"objectId":"lamp","value":{"__id__": "lamp", "__type__": "Lamp"}}"""),
            frame("objectDeleted", """{"objectId":"lamp"}"""),
            frame("stateChange", """{"stack":["off"],"modelElementId":"lamp"}"""),
            frame("objectDeleted", """{"objectId":"lamp","revision":1.5}"""),
            frame("objectDeleted", """{"objectId":"lamp","revision":"1"}"""),
        ).forEach { text ->
            val violation = violationOf(text)
            assertEquals(ViolationKind.MALFORMED_PAYLOAD, violation.kind, text)
            assertTrue("revision" in violation.detail, violation.detail)
        }
    }

    @Test
    fun `a malformed payload keeps the message type and object id it could read`() {
        val violation = violationOf(frame("objectChanged", """{"objectId":"lamp","revision":4,"value":{"__id__": "lamp"}}"""))

        assertEquals(ViolationKind.MALFORMED_PAYLOAD, violation.kind)
        assertEquals("objectChanged", violation.messageType)
        assertEquals("lamp", violation.objectId)
    }

    @Test
    fun `an objectId that differs from the value's id is reported alongside the push applied under the value's id`() {
        val decoded = pushOf(frame("objectChanged", """{"objectId":"lamp","revision":4,"value":{"__id__": "switch", "__type__": "Switch"}}"""))

        assertEquals("switch", assertIs<RuntimePush.ObjectChanged>(decoded.push).objectId)
        val violation = decoded.violations.single()
        assertEquals(ViolationKind.ID_MISMATCH, violation.kind)
        assertEquals("lamp", violation.objectId)
        assertEquals("objectChanged", violation.messageType)
    }

    @Test
    fun `a published event with a missing or mistyped member leaves the event null and adds a violation`() {
        val inner = """{"event":"start","payload":"","payloadFormat":"none","source":"INTERNAL","timestamp":"soon","ttlMs":3000}"""

        val decoded = pushOf(frame("domainEvent", """{"domain":"public","payload":$inner}"""))

        val push = assertIs<RuntimePush.DomainEvent>(decoded.push)
        assertNull(push.event)
        assertEquals(inner, push.rawPayload)
        assertEquals(ViolationKind.MALFORMED_PAYLOAD, decoded.violations.single().kind)
    }

    @Test
    fun `a push of an unknown type is unrecognized with its payload written compactly`() {
        val decoded = pushOf(frame("transitionFired", """{ "from" : "off", "to": ["on", 2.0, true, null] }"""))

        assertEquals(RuntimePush.Unrecognized("transitionFired", """{"from":"off","to":["on",2,true,null]}"""), decoded.push)
        assertEquals(emptyList(), decoded.violations)
    }

    @Test
    fun `a push to another environment or client is foreign`() {
        val otherEnvironment = RuntimeWireWriter.envelope("env-1", client, "objectDeleted", """{"objectId":"lamp","revision":1}""")
        val otherClient = RuntimeWireWriter.envelope(environment, "karpfen-ide-000000000000", "objectDeleted", """{"objectId":"lamp","revision":1}""")

        listOf(otherEnvironment, otherClient).forEach { text ->
            val violation = violationOf(text)
            assertEquals(ViolationKind.FOREIGN_ENVELOPE, violation.kind)
            assertEquals("objectDeleted", violation.messageType)
            assertEquals("lamp", violation.objectId)
        }
    }

    @Test
    fun `an envelope without its strings or its payload is malformed`() {
        listOf(
            """{"clientId":"$client","messageType":"objectDeleted","payload":{}}""",
            """{"environmentKey":"$environment","messageType":"objectDeleted","payload":{}}""",
            """{"environmentKey":"$environment","clientId":"$client","messageType":7,"payload":{}}""",
            """{"environmentKey":"$environment","clientId":"$client","messageType":"objectDeleted"}""",
            """["objectDeleted"]""",
            frame("stateChange", "[]"),
        ).forEach { text -> assertEquals(ViolationKind.MALFORMED_PAYLOAD, violationOf(text).kind, text) }
    }

    @Test
    fun `a frame that is not the runtime's JSON is undecodable, with salvaged names and a short excerpt`() {
        val text = """{"environmentKey":"$environment","clientId":"$client","messageType":"objectChanged","payload":{"objectId":"lamp",""" +
            "\"value\":{\"__id__\": \"lamp\", \"note\": \"${"x".repeat(300)}\"}},}"

        val violation = violationOf(text)

        assertEquals(ViolationKind.UNDECODABLE_FRAME, violation.kind)
        assertEquals("objectChanged", violation.messageType)
        assertEquals("lamp", violation.objectId)
        assertEquals(text.take(200), violation.excerpt)
    }
}
