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
package io.karpfen.ide.notation.diagram.layout

import io.karpfen.ide.notation.diagram.LampStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests the changes by hand and the file form of the layout they produce. */
class VisEditingTest {

    private val text = TextMeasurer.estimating()
    private val lamp = LampStateMachine.diagramOf(LampStateMachine.LAMP)
    private val automatic = StatechartLayouter().layout(lamp, text)
    private val frozen = VisEditing.freeze(lamp, automatic)

    @Test
    fun `freezing keeps every state where the automatic layout drew it, on the grid`() {
        assertEquals(setOf("off", "full", "brighter", "broken"), frozen.boxes.keys, "states with nested states have no place of their own")
        frozen.boxes.forEach { (name, point) ->
            assertEquals(DiagramGrid.snap(point), point, "$name is off the grid")
            val drawn = automatic.states.getValue(name).center
            assertTrue(Geometry.distance(drawn, point) <= DiagramGrid.STEP, "$name jumped from $drawn to $point")
        }
        val byHand = ManualStatechartLayout.of(lamp, frozen, text)
        assertEquals(automatic.states.keys, byHand.states.keys)
    }

    @Test
    fun `moving a state moves it on the grid and nothing else`() {
        val moved = VisEditing.moveState(lamp, frozen, "off", DiagramPoint(23f, -8f))

        assertEquals(DiagramPoint(frozen.boxes.getValue("off").x + 20f, frozen.boxes.getValue("off").y - 10f), moved.boxes.getValue("off"))
        assertEquals(frozen.boxes - "off", moved.boxes - "off")
        assertEquals(frozen, VisEditing.moveState(lamp, frozen, "off", DiagramPoint(3f, 2f)), "a move of less than half a step is none")
    }

    @Test
    fun `moving a state with nested states takes them along`() {
        val moved = VisEditing.moveState(lamp, frozen, "on", DiagramPoint(50f, 30f))

        listOf("full", "brighter").forEach { name ->
            val before = frozen.boxes.getValue(name)
            assertEquals(DiagramPoint(before.x + 50f, before.y + 30f), moved.boxes.getValue(name), name)
        }
        assertEquals(frozen.boxes.getValue("off"), moved.boxes.getValue("off"))
    }

    @Test
    fun `a waypoint is added into the nearest segment, moved on the grid and removed again`() {
        val id = "NORMAL:off->broken#1"
        val straight = frozen.copy(waypoints = emptyMap())
        val line = ManualStatechartLayout.of(lamp, straight, text).routes.single { it.transitionId == id }.points

        val one = VisEditing.addWaypoint(straight, id, DiagramPoint(43f, 187f), line)
        assertEquals(listOf(DiagramPoint(40f, 190f)), one.waypoints.getValue(id))

        val bent = ManualStatechartLayout.of(lamp, one, text).routes.single { it.transitionId == id }.points
        val two = VisEditing.addWaypoint(one, id, DiagramPoint(bent.last().x, bent.last().y - 12f), bent)
        assertEquals(DiagramPoint(40f, 190f), two.waypoints.getValue(id).first(), "the new point comes after the first one")
        assertEquals(2, two.waypoints.getValue(id).size)

        val moved = VisEditing.moveWaypoint(two, id, 0, DiagramPoint(-17f, 204f))
        assertEquals(DiagramPoint(-20f, 200f), moved.waypoints.getValue(id).first())
        assertEquals(id to 0, VisEditing.waypointAt(moved, setOf(id), DiagramPoint(-18f, 203f)))
        assertNull(VisEditing.waypointAt(moved, setOf(id), DiagramPoint(500f, 900f)))

        val removed = VisEditing.removeWaypoint(VisEditing.removeWaypoint(moved, id, 0), id, 0)
        assertNull(removed.waypoints[id])
    }

    @Test
    fun `the text wins - what the text no longer has is dropped`() {
        val stale = frozen.copy(
            boxes = frozen.boxes + ("gone" to DiagramPoint(0f, 0f)) + ("on" to DiagramPoint(0f, 0f)),
            bars = mapOf("SPLIT:a->b,c#1" to DiagramPoint(0f, 0f)),
            waypoints = frozen.waypoints + ("NORMAL:gone->off#1" to listOf(DiagramPoint(0f, 0f))),
        )

        val pruned = VisEditing.prune(lamp, stale)

        assertEquals(frozen.boxes, pruned.boxes, "a state that is gone or has nested states now has no place")
        assertEquals(emptyMap(), pruned.bars)
        assertEquals(frozen.waypoints.filterValues { it.isNotEmpty() }, pruned.waypoints)
    }

    @Test
    fun `the file is JSON that reads back to the same layout`() {
        val layout = VisLayout(
            boxes = mapOf("off" to DiagramPoint(120f, 40f), "full power" to DiagramPoint(-30f, 12.5f)),
            bars = mapOf("SPLIT:idle->left,right#1" to DiagramPoint(200f, 230f)),
            waypoints = mapOf("NORMAL:off->on#1" to listOf(DiagramPoint(200f, 90f), DiagramPoint(210f, 90f))),
        )

        val file = VisLayoutJson.encode(layout)

        assertTrue("\"off\"" in file && "\"x\": 120" in file, file)
        assertEquals(layout, VisLayoutJson.decode(file))
        assertEquals(VisLayout(), VisLayoutJson.decode(VisLayoutJson.encode(VisLayout())))
    }

    @Test
    fun `reading a file is tolerant`() {
        assertNull(VisLayoutJson.decode("not json"))
        assertNull(VisLayoutJson.decode("[1, 2]"))
        assertEquals(VisLayout(), VisLayoutJson.decode("{}"))
        assertEquals(
            VisLayout(boxes = mapOf("ok" to DiagramPoint(1f, 2f))),
            VisLayoutJson.decode("""{ "states": { "ok": { "x": 1, "y": 2 }, "bad": { "x": "left" }, "worse": 7 }, "future": true }"""),
        )
    }
}
