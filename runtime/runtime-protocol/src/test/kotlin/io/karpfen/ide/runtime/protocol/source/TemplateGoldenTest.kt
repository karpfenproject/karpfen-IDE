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
package io.karpfen.ide.runtime.protocol.source

import io.karpfen.ide.runtime.protocol.golden.FakeObject
import io.karpfen.ide.runtime.protocol.golden.RuntimeGoldens
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.golden.fakeObject
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the runtime goldens against the runtime's own templates and against the port of its writers. The templates
 * are extracted from the runtime checkout and interpolated with the goldens' values. They are the envelope of
 * `OutgoingMessage.toJson`, the `objectChanged`, `objectDeleted` and `domainEvent` payloads of `ClientSessionManager`,
 * and the snapshot of `ModelSnapshot.toJson`. Each one writes its golden byte for byte. [RuntimeWireWriter] writes the
 * goldens too. That includes the org.json payloads and the object goldens built as [FakeObject]s. The KDoc of
 * [RuntimeSourceAnchorTest] describes what to do when the karpfen-runtime submodule moves.
 */
class TemplateGoldenTest {

    private val checkout = RuntimeSourceCheckout.fromSystemProperty()

    @Test
    fun `the runtime's envelope template writes the four push goldens`() {
        val envelope = KotlinTemplateLiteral.startingWith(checkout.text(CLIENT_SESSION), "{\"environmentKey\":")

        GOLDEN_PUSHES.forEach { (messageType, payload, frame) ->
            val samples = mapOf(
                "ModelJsonWriter.quote(environmentKey)" to RuntimeWireWriter.quote(RuntimeGoldens.ENVIRONMENT),
                "ModelJsonWriter.quote(clientId)" to RuntimeWireWriter.quote(RuntimeGoldens.CLIENT),
                "ModelJsonWriter.quote(messageType)" to RuntimeWireWriter.quote(messageType),
                "payload" to payload,
            )
            assertEquals(frame, envelope.interpolate(samples), messageType)
        }
    }

    @Test
    fun `the session manager's payload templates write the payload goldens`() {
        val sessions = checkout.text(SESSION_MANAGER)
        val objectPrefix = "{\"objectId\":\${ModelJsonWriter.quote(objectId)},\"revision\":\$revision"
        val changed = KotlinTemplateLiteral.startingWith(sessions, "$objectPrefix,")
        val deleted = KotlinTemplateLiteral.startingWith(sessions, "$objectPrefix}")
        val domainEvent = KotlinTemplateLiteral.startingWith(sessions, "{\"domain\":")

        assertEquals(
            RuntimeGoldens.OBJECT_CHANGED_PAYLOAD,
            changed.interpolate(
                mapOf(
                    "ModelJsonWriter.quote(objectId)" to RuntimeWireWriter.quote("turtlePosition"),
                    "revision" to "58",
                    "newValue" to RuntimeGoldens.TURTLE_POSITION,
                ),
            ),
        )
        assertEquals(
            RuntimeGoldens.OBJECT_DELETED_PAYLOAD,
            deleted.interpolate(mapOf("ModelJsonWriter.quote(objectId)" to RuntimeWireWriter.quote("gen_Drone_4"), "revision" to "59")),
        )
        assertEquals(
            RuntimeGoldens.DOMAIN_EVENT_PAYLOAD,
            domainEvent.interpolate(
                mapOf("ModelJsonWriter.quote(domain)" to RuntimeWireWriter.quote("public"), "payload" to RuntimeGoldens.PUBLISHED_EVENT),
            ),
        )
    }

    @Test
    fun `the runtime's snapshot template writes the lamp's snapshot golden`() {
        val snapshot = KotlinTemplateLiteral.concatenationStartingWith(checkout.text(MODEL_SNAPSHOT), "{\\\"environmentKey\\\":")

        val written = snapshot.interpolate(
            mapOf(
                "ModelJsonWriter.quote(environmentKey)" to RuntimeWireWriter.quote(RuntimeGoldens.ENVIRONMENT),
                "ModelJsonWriter.quote(phase.wireName)" to RuntimeWireWriter.quote(RuntimePhase.ACTIVATED.wireName),
                "revision" to "0",
                "states" to "",
                "roots.joinToString(\",\")" to RuntimeGoldens.LAMP_ROOT,
            ),
        )

        assertEquals(RuntimeGoldens.LAMP_SNAPSHOT_BEFORE_START, written)
    }

    @Test
    fun `the wire writer writes the push goldens`() {
        val turtlePosition = fakeObject("turtlePosition", "Vector") {
            number("x", 5.0)
            number("y", 5.3)
        }

        assertEquals(RuntimeGoldens.TURTLE_POSITION, RuntimeWireWriter.objectJson(turtlePosition))
        assertEquals(
            RuntimeGoldens.OBJECT_CHANGED_PAYLOAD,
            RuntimeWireWriter.objectChangedPayload("turtlePosition", 58, RuntimeGoldens.TURTLE_POSITION),
        )
        assertEquals(RuntimeGoldens.OBJECT_DELETED_PAYLOAD, RuntimeWireWriter.objectDeletedPayload("gen_Drone_4", 59))
        assertEquals(RuntimeGoldens.STATE_CHANGE_PAYLOAD, RuntimeWireWriter.stateChangePayload("turtle", listOf("observe"), 60))
        assertEquals(
            RuntimeGoldens.DOMAIN_EVENT_PAYLOAD,
            RuntimeWireWriter.domainEventPayload("public", "start", "", "none", "EXTERNAL_MESSAGE", 1_761_560_130_123, 3000),
        )
        GOLDEN_PUSHES.forEach { (messageType, payload, frame) ->
            assertEquals(frame, RuntimeWireWriter.envelope(RuntimeGoldens.ENVIRONMENT, RuntimeGoldens.CLIENT, messageType, payload))
        }
    }

    @Test
    fun `the wire writer writes the lamp's snapshot golden from the lamp's objects`() {
        val wallSwitch = fakeObject("wallSwitch", "Switch") { text("position", "down") }
        val room = fakeObject("living room", "Room") {
            has(
                "lamp",
                fakeObject("lamp", "Lamp") {
                    text("mode", "off")
                    has("bulb", bulb("bulb", 40.0))
                    has("spare", bulb("spare", 60.0))
                    knows("control", "wallSwitch")
                },
            )
            has("switch", wallSwitch)
        }

        val root = RuntimeWireWriter.objectJson(room, mapOf("wallSwitch" to wallSwitch)::get)

        assertEquals(RuntimeGoldens.LAMP_ROOT, root)
        assertEquals(
            RuntimeGoldens.LAMP_SNAPSHOT_BEFORE_START,
            RuntimeWireWriter.snapshot(RuntimeGoldens.ENVIRONMENT, RuntimePhase.ACTIVATED, 0, emptyMap(), listOf(root)),
        )
    }

    @Test
    fun `the wire writer writes the single robot's turtle golden with its known objects inlined where they are referred to`() {
        val objects = singleRobotObjects()

        assertEquals(RuntimeGoldens.TURTLE, RuntimeWireWriter.objectJson(objects.getValue("turtle"), objects::get))
    }

    @Test
    fun `the wire writer writes the edge case goldens`() {
        val note = fakeObject("note", "Note") { text("text", "line one\nback\\slash \"quoted\" \u0001 end") }
        val sensor = fakeObject("sensor", "Sensor") {
            number("nan", Double.NaN)
            numbers("readings", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0E20)
        }
        val robot = fakeObject("robot", "Robot") {
            has("direction", fakeObject("", "Vector") { number("x", 1.0) })
            has("battery", null)
            hasMany("wheels", fakeObject("w1", "Wheel"), null)
        }

        assertEquals(RuntimeGoldens.ESCAPES_VALUE, RuntimeWireWriter.objectJson(note))
        assertEquals(RuntimeGoldens.NON_FINITE_VALUE, RuntimeWireWriter.objectJson(sensor))
        assertEquals(RuntimeGoldens.RELATIONS_VALUE, RuntimeWireWriter.objectJson(robot))
    }

    /** A bulb of the lamp model with [watts], not lit. */
    private fun bulb(id: String, watts: Double): FakeObject =
        fakeObject(id, "Bulb") {
            number("watts", watts)
            flag("lit", false)
        }

    /** A vector of the single robot example. */
    private fun vector(id: String, x: Double, y: Double): FakeObject =
        fakeObject(id, "Vector") {
            number("x", x)
            number("y", y)
        }

    /** A circular object of the single robot example with its bounding box. */
    private fun twoD(id: String, diameter: Double, position: FakeObject): FakeObject =
        fakeObject(id, "TwoDObject") {
            number("diameter", diameter)
            has("position", position)
        }

    /** The objects of the single robot example that the turtle holds or knows, by id. */
    private fun singleRobotObjects(): Map<String, FakeObject> {
        val chair = fakeObject("chair", "Obstacle") { has("boundingBox", twoD("chairBoundingBox", 1.0, vector("chairPosition", 2.0, 3.0))) }
        val table = fakeObject("table", "Obstacle") { has("boundingBox", twoD("tableBoundingBox", 3.0, vector("tablePosition", 5.0, 7.0))) }
        val walls = listOf(
            Triple("wall_top", vector("wallTopP", 0.0, 10.0), vector("wallTopUVec", 1.0, 0.0)),
            Triple("wall_right", vector("wallRightP", 10.0, 0.0), vector("wallRightUVec", 0.0, 1.0)),
            Triple("wall_bottom", vector("wallBottomP", 0.0, 0.0), vector("wallBottomUVec", 1.0, 0.0)),
            Triple("wall_left", vector("wallLeftP", 0.0, 0.0), vector("wallLeftUVec", 0.0, 1.0)),
        ).map { (id, p, uVec) ->
            fakeObject(id, "Wall") {
                has("p", p)
                has("u_vec", uVec)
            }
        }
        val turtle = fakeObject("turtle", "Robot") {
            number("d_closest_obstacle", 100.0)
            number("d_closest_wall", 100.0)
            has("boundingBox", twoD("turtleBoundingBox", 0.2, vector("turtlePosition", 5.0, 5.0)))
            has("direction", vector("turtleDirection", 0.0, 1.0))
            knowsMany("obstacles", "chair", "table")
            knowsMany("walls", "wall_top", "wall_right", "wall_bottom", "wall_left")
            knows("closest_obstacle", "chair")
            knows("closest_wall", "wall_top")
        }
        return (listOf(turtle, chair, table) + walls).associateBy { it.id }
    }

    private companion object {
        /** The runtime file of `OutgoingMessage`. */
        const val CLIENT_SESSION = "src/main/kotlin/io/karpfen/websocket/ClientSession.kt"

        /** The runtime file of `ClientSessionManager`. */
        const val SESSION_MANAGER = "src/main/kotlin/io/karpfen/websocket/ClientSessionManager.kt"

        /** The runtime file of `ModelSnapshot`. */
        const val MODEL_SNAPSHOT = "src/main/kotlin/io/karpfen/observation/ModelSnapshot.kt"

        /** The four push goldens, each with its message type, payload and frame. */
        val GOLDEN_PUSHES = listOf(
            Triple("objectChanged", RuntimeGoldens.OBJECT_CHANGED_PAYLOAD, RuntimeGoldens.OBJECT_CHANGED),
            Triple("objectDeleted", RuntimeGoldens.OBJECT_DELETED_PAYLOAD, RuntimeGoldens.OBJECT_DELETED),
            Triple("stateChange", RuntimeGoldens.STATE_CHANGE_PAYLOAD, RuntimeGoldens.STATE_CHANGE),
            Triple("domainEvent", RuntimeGoldens.DOMAIN_EVENT_PAYLOAD, RuntimeGoldens.DOMAIN_EVENT),
        )
    }
}
