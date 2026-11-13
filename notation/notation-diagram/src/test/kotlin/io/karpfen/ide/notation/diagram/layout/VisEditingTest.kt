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
import kotlin.test.assertNotNull
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
    fun `fixing the layout keeps every label where the automatic layout drew it`() {
        val kept = ManualStatechartLayout.of(lamp, StatechartArrangement.frozen(lamp, automatic, text), text).labels

        automatic.labels.forEach { (id, drawn) ->
            val now = assertNotNull(kept[id], id)
            assertTrue(Geometry.distance(drawn.center, now.center) <= DiagramGrid.STEP, "the label of $id jumped from $drawn to $now")
        }
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
        val line = ManualStatechartLayout.of(lamp, straight, text).routes.single { it.edgeId == id }.points

        val one = VisEditing.addWaypoint(straight, id, DiagramPoint(43f, 187f), line)
        assertEquals(listOf(DiagramPoint(40f, 190f)), one.waypoints.getValue(id))

        val bent = ManualStatechartLayout.of(lamp, one, text).routes.single { it.edgeId == id }.points
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
    fun `a label moves away from its usual place in steps of the grid and keeps that distance`() {
        val id = "NORMAL:off->broken#1"
        val before = assertNotNull(ManualStatechartLayout.of(lamp, frozen, text).routes.single { it.edgeId == id }.label)

        val moved = VisEditing.moveLabel(frozen, id, DiagramPoint(27f, -12f))
        val again = VisEditing.moveLabel(moved, id, DiagramPoint(-10f, 0f))

        assertEquals(DiagramPoint(30f, -10f), moved.labels[id])
        assertEquals(DiagramPoint(20f, -10f), again.labels[id])
        assertEquals(frozen, VisEditing.moveLabel(frozen, id, DiagramPoint(3f, 2f)), "a move of less than half a step is none")
        val after = assertNotNull(ManualStatechartLayout.of(lamp, moved, text).routes.single { it.edgeId == id }.label)
        assertEquals(DiagramBox(before.x + 30f, before.y - 10f, before.width, before.height), after)

        val straight = moved.copy(waypoints = emptyMap())
        val here = assertNotNull(ManualStatechartLayout.of(lamp, straight, text).routes.single { it.edgeId == id }.label)
        val elsewhere = VisEditing.moveState(lamp, straight, "broken", DiagramPoint(100f, 0f))
        val followed = assertNotNull(ManualStatechartLayout.of(lamp, elsewhere, text).routes.single { it.edgeId == id }.label)
        assertTrue(followed.x > here.x, "the label follows its line")
    }

    @Test
    fun `freezing keeps the box of a state with nested states, and the state can be made larger but not too small`() {
        val on = automatic.states.getValue("on")
        val frame = frozen.frames.getValue("on")
        assertEquals(setOf("on"), frozen.frames.keys)
        assertTrue(kotlin.math.abs(frame.x - on.x) <= DiagramGrid.STEP && kotlin.math.abs(frame.right - on.right) <= DiagramGrid.STEP, "$frame is not $on")

        val larger = DiagramBox(frame.x - 50f, frame.y - 20f, frame.width + 120f, frame.height + 60f)
        val resized = VisEditing.resize(lamp, frozen, "on", larger)
        assertEquals(larger, ManualStatechartLayout.of(lamp, resized, text).states.getValue("on"))

        val tiny = VisEditing.resize(lamp, frozen, "on", DiagramBox(frame.x + 40f, frame.y + 40f, 20f, 20f))
        val least = ManualStatechartLayout.of(lamp, frozen.copy(frames = emptyMap()), text).states.getValue("on")
        val drawn = ManualStatechartLayout.of(lamp, tiny, text).states.getValue("on")
        assertTrue(drawn.x <= least.x && drawn.right >= least.right && drawn.y <= least.y && drawn.bottom >= least.bottom, "$drawn cuts its inner states")

        assertEquals(frozen, VisEditing.resize(lamp, frozen, "off", larger), "a state without nested states has no frame")
    }

    @Test
    fun `moving a state with nested states takes its frame along`() {
        val moved = VisEditing.moveState(lamp, frozen, "on", DiagramPoint(40f, 30f))

        val before = frozen.frames.getValue("on")
        assertEquals(DiagramBox(before.x + 40f, before.y + 30f, before.width, before.height), moved.frames.getValue("on"))
    }

    @Test
    fun `a hold on a border moves that side onto the grid and never past the other side`() {
        val box = DiagramBox(100f, 100f, 200f, 100f)

        val corner = assertNotNull(FrameGrip.on("on", box, DiagramPoint(298f, 203f), 6f))
        assertEquals(FrameGrip("on", left = false, top = false, right = true, bottom = true), corner)
        assertEquals(DiagramBox(100f, 100f, 250f, 140f), corner.resized(box, DiagramPoint(352f, 238f)))
        assertEquals(DiagramBox(100f, 100f, 20f, 20f), corner.resized(box, DiagramPoint(0f, 0f)))

        val left = assertNotNull(FrameGrip.on("on", box, DiagramPoint(97f, 150f), 6f))
        assertEquals(FrameGrip("on", left = true, top = false, right = false, bottom = false), left)
        assertEquals(DiagramBox(60f, 100f, 240f, 100f), left.resized(box, DiagramPoint(61f, 0f)))
        assertNull(FrameGrip.on("on", box, DiagramPoint(200f, 150f), 6f), "the inside is no border")
        assertNull(FrameGrip.on("on", box, DiagramPoint(320f, 150f), 6f), "too far outside")
    }

    @Test
    fun `a name counts as changed if the old one is gone and the new one was not there`() {
        assertEquals(mapOf("off" to "dark"), VisEditing.renamed(listOf("off", "on", "broken"), listOf("dark", "on", "broken")))
        assertEquals(mapOf("a" to "x", "c" to "z"), VisEditing.renamed(listOf("a", "b", "c"), listOf("x", "b", "z")))
        assertEquals(emptyMap(), VisEditing.renamed(listOf("a", "b"), listOf("b", "a")), "changed places are no renaming")
        assertEquals(emptyMap(), VisEditing.renamed(listOf("a", "b"), listOf("a", "b", "c")), "an added element is no renaming")
        assertEquals(emptyMap(), VisEditing.renamed(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun `a state renamed in the text keeps its place, and so do its lines once the transitions name it`() {
        val moved = VisEditing.moveLabel(frozen, "NORMAL:off->full#1", DiagramPoint(20f, 0f))
            .let { it.copy(waypoints = it.waypoints + ("NORMAL:off->full#1" to listOf(DiagramPoint(50f, 60f)))) }
        val declared = LampStateMachine.diagramOf(LampStateMachine.LAMP.replace("INITIAL STATE \"off\"", "INITIAL STATE \"dark\""))

        val first = StatechartArrangement.followed(lamp, declared, moved)

        assertEquals(moved.boxes.getValue("off"), first.boxes["dark"])
        assertNull(first.boxes["off"])
        assertEquals(moved.waypoints, first.waypoints, "the transitions still name the old state, so their lines keep their ids")

        val everywhere = LampStateMachine.diagramOf(LampStateMachine.LAMP.replace("\"off\"", "\"dark\""))
        val second = StatechartArrangement.followed(declared, everywhere, first)

        assertEquals(listOf(DiagramPoint(50f, 60f)), second.waypoints["NORMAL:dark->full#1"])
        assertEquals(DiagramPoint(20f, 0f), second.labels["NORMAL:dark->full#1"])
        assertNull(second.waypoints["NORMAL:off->full#1"])
        assertEquals(frozen, StatechartArrangement.followed(lamp, lamp, frozen))
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
        val more = VisEditing.prune(
            lamp,
            frozen.copy(
                labels = mapOf("NORMAL:off->broken#1" to DiagramPoint(10f, 0f), "NORMAL:gone->off#1" to DiagramPoint(10f, 0f)),
                frames = frozen.frames + ("off" to DiagramBox(0f, 0f, 10f, 10f)) + ("gone" to DiagramBox(0f, 0f, 10f, 10f)),
            ),
        )
        assertEquals(setOf("NORMAL:off->broken#1"), more.labels.keys)
        assertEquals(setOf("on"), more.frames.keys)
        assertEquals(emptyMap(), pruned.bars)
        assertEquals(frozen.waypoints.filterValues { it.isNotEmpty() }, pruned.waypoints)
    }

    @Test
    fun `the file is JSON that reads back to the same layout`() {
        val layout = VisLayout(
            boxes = mapOf("off" to DiagramPoint(120f, 40f), "full power" to DiagramPoint(-30f, 12.5f)),
            bars = mapOf("SPLIT:idle->left,right#1" to DiagramPoint(200f, 230f)),
            waypoints = mapOf("NORMAL:off->on#1" to listOf(DiagramPoint(200f, 90f), DiagramPoint(210f, 90f))),
            labels = mapOf("NORMAL:off->on#1" to DiagramPoint(20f, -10f), "NORMAL:on->off#1" to DiagramPoint(0f, 30f)),
            frames = mapOf("on" to DiagramBox(60f, 120f, 300f, 160f)),
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
