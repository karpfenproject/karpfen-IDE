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

import io.karpfen.ide.notation.diagram.LampStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests the layout of class and object diagrams: the automatic one, the one by hand, and the changes by hand. */
class StructureLayoutTest {

    private val text = TextMeasurer.estimating()
    private val types = LampStructure.typesOf(LampStructure.METAMODEL)
    private val automatic = StructureArrangement.automatic(types, text)

    @Test
    fun `every type gets a box that is large enough for its texts, and no two overlap`() {
        assertEquals(listOf("Bulb", "Switch", "Lamp", "Room"), automatic.boxes.keys.toList())
        val bulb = automatic.boxes.getValue("Bulb")
        assertTrue(bulb.width >= text.widthOf("maxWatts: number") + 2 * StructureMetrics.TEXT_PADDING, "$bulb")
        assertEquals(StructureMetrics.TITLE_HEIGHT + 2 * StructureMetrics.ROW_HEIGHT + 2 * StructureMetrics.ROWS_PADDING, bulb.height)
        val boxes = automatic.boxes.values.toList()
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val a = boxes[i]
                val b = boxes[j]
                assertTrue(a.right <= b.x || b.right <= a.x || a.bottom <= b.y || b.bottom <= a.y, "$a overlaps $b")
            }
        }
    }

    @Test
    fun `what owns something stands above it, and every link gets a line with a label`() {
        assertTrue(automatic.boxes.getValue("Room").bottom <= automatic.boxes.getValue("Lamp").y, "Room is not above Lamp")
        assertTrue(automatic.boxes.getValue("Lamp").bottom <= automatic.boxes.getValue("Bulb").y, "Lamp is not above Bulb")
        assertEquals(types.links.map { it.id }, automatic.routes.map { it.linkId })
        automatic.routes.forEach { route ->
            assertTrue(route.points.size >= 2, "${route.linkId} has no path")
            assertNotNull(route.label, "${route.linkId} has no label")
        }
    }

    @Test
    fun `freezing keeps every box near its place and on the grid`() {
        val frozen = StructureArrangement.freeze(types, automatic)
        val byHand = StructureArrangement.byHand(types, frozen, text)

        assertEquals(automatic.boxes.keys, frozen.boxes.keys)
        frozen.boxes.forEach { (id, point) ->
            assertEquals(DiagramGrid.snap(point), point, "$id is off the grid")
            assertTrue(Geometry.distance(automatic.boxes.getValue(id).center, point) <= DiagramGrid.STEP, "$id jumped")
            assertEquals(point, byHand.boxes.getValue(id).center)
        }
        assertEquals(automatic.routes.map { it.linkId }, byHand.routes.map { it.linkId })
    }

    @Test
    fun `fixing the layout keeps every label where the automatic layout drew it`() {
        val frozen = StructureArrangement.frozen(types, automatic, text)
        val byHand = StructureArrangement.byHand(types, frozen, text)

        automatic.labels.forEach { (id, drawn) ->
            val kept = assertNotNull(byHand.labels[id], id)
            assertTrue(Geometry.distance(drawn.center, kept.center) <= DiagramGrid.STEP, "the label of $id jumped from $drawn to $kept")
        }
        assertEquals(StructureArrangement.freeze(types, automatic).boxes, frozen.boxes, "nothing else is different")
    }

    @Test
    fun `a box by hand sits on its stored point, and a new one is placed right of the others`() {
        val vis = VisLayout(boxes = mapOf("Bulb" to DiagramPoint(100f, 300f), "Lamp" to DiagramPoint(100f, 100f)))

        val layout = ManualStructureLayout.of(types, vis, text)

        assertEquals(DiagramPoint(100f, 300f), layout.boxes.getValue("Bulb").center)
        assertEquals(DiagramPoint(100f, 100f), layout.boxes.getValue("Lamp").center)
        val placed = layout.boxes.getValue("Bulb").union(layout.boxes.getValue("Lamp"))
        val switch = layout.boxes.getValue("Switch")
        val room = layout.boxes.getValue("Room")
        assertTrue(switch.x >= placed.right + StructureMetrics.GAP - DiagramGrid.STEP, "Switch is not right of the placed boxes: $switch")
        assertTrue(room.x >= switch.right + StructureMetrics.GAP - DiagramGrid.STEP, "Room is not right of Switch: $room")
        assertEquals(DiagramGrid.snap(switch.center), switch.center)
    }

    @Test
    fun `two links between the same boxes run side by side and meet the boxes at a right angle`() {
        val vis = VisLayout(boxes = mapOf("Bulb" to DiagramPoint(100f, 300f), "Lamp" to DiagramPoint(100f, 100f)))

        val layout = ManualStructureLayout.of(types, vis, text)
        val bulb = layout.routes.single { it.linkId == "has:Lamp.bulb" }.points
        val spare = layout.routes.single { it.linkId == "has:Lamp.spare" }.points

        assertEquals(2, bulb.size)
        assertEquals(bulb.first().x, bulb.last().x, "the line is not upright")
        assertEquals(spare.first().x, spare.last().x, "the line is not upright")
        assertNotEquals(bulb.first().x, spare.first().x, "the two lines lie on top of each other")
        assertEquals(layout.boxes.getValue("Lamp").bottom, bulb.first().y)
        assertEquals(layout.boxes.getValue("Bulb").y, bulb.last().y)
    }

    @Test
    fun `the label of a link stands on the side of its line where it covers no other line`() {
        val vis = VisLayout(boxes = mapOf("Bulb" to DiagramPoint(100f, 300f), "Lamp" to DiagramPoint(100f, 100f)))

        val layout = ManualStructureLayout.of(types, vis, text)
        val bulb = layout.routes.single { it.linkId == "has:Lamp.bulb" }
        val spare = layout.routes.single { it.linkId == "has:Lamp.spare" }

        val left = assertNotNull(bulb.label)
        val right = assertNotNull(spare.label)
        assertTrue(left.right <= bulb.points.first().x, "the label of the left line is not left of it: $left")
        assertTrue(right.x >= spare.points.first().x, "the label of the right line is not right of it: $right")
        assertTrue(!Geometry.crosses(left, spare.points) && !Geometry.crosses(right, bulb.points))
    }

    @Test
    fun `lines that the automatic layout draws side by side stay apart after freezing`() {
        val frozen = StructureArrangement.freeze(types, automatic)
        val byHand = StructureArrangement.byHand(types, frozen, text)

        val bulb = byHand.routes.single { it.linkId == "has:Lamp.bulb" }.points
        val spare = byHand.routes.single { it.linkId == "has:Lamp.spare" }.points
        assertTrue(Geometry.distance(bulb.first(), spare.first()) >= DiagramGrid.STEP, "the lines leave Lamp at the same point")
        assertTrue(Geometry.distance(bulb.last(), spare.last()) >= DiagramGrid.STEP, "the lines reach Bulb at the same point")
    }

    @Test
    fun `a link runs through its waypoints, and a link from a box to itself gets a loop`() {
        val tree = LampStructure.typesOf("""type "Node" "A node of a tree" { has("children", list("Node")) }""")
        val loop = ManualStructureLayout.of(tree, VisLayout(boxes = mapOf("Node" to DiagramPoint(0f, 0f))), text).routes.single()
        val box = ManualStructureLayout.of(tree, VisLayout(boxes = mapOf("Node" to DiagramPoint(0f, 0f))), text).boxes.getValue("Node")
        assertEquals(4, loop.points.size)
        assertEquals(box.right, loop.points.first().x)
        assertEquals(box.right, loop.points.last().x)
        assertTrue(loop.points[1].x > box.right)

        val way = DiagramPoint(300f, 200f)
        val vis = VisLayout(
            boxes = mapOf("Bulb" to DiagramPoint(100f, 300f), "Lamp" to DiagramPoint(100f, 100f)),
            waypoints = mapOf("has:Lamp.bulb" to listOf(way)),
        )
        val bent = ManualStructureLayout.of(types, vis, text).routes.single { it.linkId == "has:Lamp.bulb" }
        assertEquals(way, bent.points[1])
        assertEquals(3, bent.points.size)
    }

    @Test
    fun `moving a box moves only that box, in steps of the grid`() {
        val frozen = StructureArrangement.freeze(types, automatic)

        val moved = StructureArrangement.moveBox(types, frozen, "Lamp", DiagramPoint(23f, -8f))

        val before = frozen.boxes.getValue("Lamp")
        assertEquals(DiagramPoint(before.x + 20f, before.y - 10f), moved.boxes.getValue("Lamp"))
        assertEquals(frozen.boxes - "Lamp", moved.boxes - "Lamp")
        assertEquals(frozen, StructureArrangement.moveBox(types, frozen, "Lamp", DiagramPoint(2f, 3f)))
        assertEquals(frozen, StructureArrangement.moveBox(types, frozen, "Gone", DiagramPoint(50f, 50f)))
    }

    @Test
    fun `a type renamed in the text keeps its place and the lines that start at it`() {
        val vis = VisLayout(
            boxes = mapOf("Lamp" to DiagramPoint(100f, 100f), "Bulb" to DiagramPoint(100f, 300f)),
            waypoints = mapOf("has:Lamp.bulb" to listOf(DiagramPoint(50f, 200f))),
            labels = mapOf("has:Lamp.spare" to DiagramPoint(20f, 0f)),
        )
        val renamed = LampStructure.typesOf(LampStructure.METAMODEL.replace("type \"Lamp\"", "type \"Torch\""))

        val followed = StructureArrangement.followed(types, renamed, vis)

        assertEquals(mapOf("Torch" to DiagramPoint(100f, 100f), "Bulb" to DiagramPoint(100f, 300f)), followed.boxes)
        assertEquals(mapOf("has:Torch.bulb" to listOf(DiagramPoint(50f, 200f))), followed.waypoints)
        assertEquals(mapOf("has:Torch.spare" to DiagramPoint(20f, 0f)), followed.labels)
    }

    @Test
    fun `pruning drops what the text no longer has`() {
        val vis = VisLayout(
            boxes = mapOf("Lamp" to DiagramPoint(0f, 0f), "Gone" to DiagramPoint(10f, 10f)),
            bars = mapOf("SPLIT:a->b,c#1" to DiagramPoint(0f, 0f)),
            waypoints = mapOf("has:Lamp.bulb" to listOf(DiagramPoint(50f, 50f)), "has:Lamp.gone" to listOf(DiagramPoint(1f, 1f))),
        )

        val pruned = StructureArrangement.prune(types, vis)

        assertEquals(
            VisLayout(boxes = mapOf("Lamp" to DiagramPoint(0f, 0f)), waypoints = mapOf("has:Lamp.bulb" to listOf(DiagramPoint(50f, 50f)))),
            pruned,
        )
    }

    @Test
    fun `a layout tells which box and which line lie at a point`() {
        val lamp = automatic.boxes.getValue("Lamp")
        assertEquals("Lamp", automatic.boxAt(lamp.center))
        assertNull(automatic.boxAt(DiagramPoint(-500f, -500f)))
        assertNull(automatic.barAt(lamp.center))
        val route = automatic.routes.first()
        val onLine = DiagramPoint((route.points[0].x + route.points[1].x) / 2f, (route.points[0].y + route.points[1].y) / 2f)
        assertNotNull(automatic.lineAt(onLine, DiagramLayout.HIT_TOLERANCE))
        assertNull(automatic.lineAt(DiagramPoint(-500f, -500f), DiagramLayout.HIT_TOLERANCE))
        assertEquals(StructureLayout.EMPTY, StructureArrangement.automatic(LampStructure.typesOf(""), text))
    }

    @Test
    fun `the object diagram of a model is laid out like a class diagram`() {
        val objects = LampStructure.objectsOf(LampStructure.MODEL)

        val layout = StructureArrangement.automatic(objects, text)

        assertEquals(objects.nodes.map { it.id }, layout.boxes.keys.toList())
        assertEquals(objects.links.map { it.id }, layout.routes.map { it.linkId })
        assertTrue(layout.boxes.getValue("living room").bottom <= layout.boxes.getValue("lamp").y)
        assertEquals(objects.links.map { it.id }.toSet(), StructureArrangement.lines(objects))
        assertEquals(objects.node("lamp")?.nameRange, StructureArrangement.placeInText(objects, "lamp"))
    }
}
