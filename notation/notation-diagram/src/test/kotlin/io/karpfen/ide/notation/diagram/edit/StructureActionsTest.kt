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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.notation.diagram.LampStructure
import io.karpfen.ide.notation.diagram.edit.StructureTexts.applied
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StructureArrangement
import io.karpfen.ide.notation.diagram.layout.StructureLayout
import io.karpfen.ide.notation.diagram.layout.StructureMetrics
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The context menus of the class diagram and the object diagram: which entries a type, an object, a row, a link and
 * the empty canvas offer, with the values their fields start with, and what a chain of entries writes.
 */
class StructureActionsTest {

    private val meta = LampStructure.METAMODEL
    private val types = LampStructure.typesOf(meta)
    private val typeLayout = StructureArrangement.automatic(types, TextMeasurer.estimating())

    private val model = LampStructure.MODEL
    private val objects = LampStructure.objectsOf(model)
    private val objectLayout = StructureArrangement.automatic(objects, TextMeasurer.estimating())

    private val canvas = DiagramPoint(-500f, -500f)

    @Test
    fun `the canvas of a class diagram adds a type, and that of an object diagram the root object while there is none`() {
        assertEquals(listOf("Add type"), labels(TypeActions.at(meta, types, typeLayout, canvas)))
        assertEquals(emptyList(), labels(ObjectActions.at(model, objects, objectLayout, canvas)))

        val nothing = StructureDiagram(StructureKind.OBJECTS, emptyList(), emptyList())
        val entries = ObjectActions.at("", nothing, StructureLayout.EMPTY, canvas)
        assertEquals(listOf("Add root object"), labels(entries))
        assertEquals("make object \"object\":\"\" { }\n", applied(assertIs<DiagramAction.Change>(entries.single()).change(), ""))
    }

    @Test
    fun `a type offers its own entries, behaves after always and make root type only where it is not`() {
        val lamp = TypeActions.at(meta, types, typeLayout, onTitle(types, typeLayout, "Lamp"))
        assertEquals(
            listOf(
                "Rename", "Edit as text", "Describe", "Behaves after", "Add property", "Add has relation to", "Add knows relation to",
                "Make root type", "Delete",
            ),
            labels(lamp),
        )
        assertEquals("", ask(lamp, "Behaves after").initial)
        assertEquals(types.nodes.map { it.id }.toSet(), assertIs<DiagramAction.Pick>(lamp.first { it.label == "Add has relation to" }).candidates)

        val room = labels(TypeActions.at(meta, types, typeLayout, onTitle(types, typeLayout, "Room")))
        assertEquals(false, "Make root type" in room, "Room is the root type")
    }

    @Test
    fun `a property row of a type renames, sets the type, makes a list and moves`() {
        val first = TypeActions.at(meta, types, typeLayout, onRow(types, typeLayout, "Bulb", 0))
        assertEquals(listOf("Rename", "Set type", "Make list", "Move down", "Edit as text", "Delete"), labels(first))
        assertEquals("maxWatts", ask(first, "Rename").initial)
        assertEquals("number", ask(first, "Set type").initial)

        val list = TypeActions.at(meta, types, typeLayout, onRow(types, typeLayout, "Switch", 0))
        assertEquals(listOf("Rename", "Set type", "Make single value", "Edit as text", "Delete"), labels(list))
        assertEquals("string", ask(list, "Set type").initial)
    }

    @Test
    fun `a relation offers the same entries as a line and as a row, and a row also moves`() {
        val line = TypeActions.at(meta, types, typeLayout, onLabel(typeLayout, "has:Room.lamps"))
        assertEquals(
            listOf("Rename relation", "Set target type", "Make knows relation", "Make single value", "Edit as text", "Delete relation"),
            labels(line),
        )
        assertEquals("lamps", ask(line, "Rename relation").initial)
        assertEquals("Lamp", ask(line, "Set target type").initial)

        val known = labels(TypeActions.at(meta, types, typeLayout, onLabel(typeLayout, "knows:Lamp.control")))
        assertEquals("Make has relation", known[2])
        assertEquals("Make list", known[3])

        val row = TypeActions.at(meta, types, typeLayout, onRow(types, typeLayout, "Lamp", 1))
        assertEquals(
            listOf("Rename relation", "Set target type", "Make knows relation", "Make list", "Move up", "Edit as text", "Delete relation"),
            labels(row),
        )
        assertEquals("Shade", ask(row, "Set target type").initial)
    }

    @Test
    fun `the row of a behaves after clause sets the state machine and takes it away`() {
        val behaving = applied(TypeEdits.behaveAfter(meta, types, "Bulb", "glowing"), meta)
        val diagram = LampStructure.typesOf(behaving)
        val layout = StructureArrangement.automatic(diagram, TextMeasurer.estimating())

        val row = TypeActions.at(behaving, diagram, layout, onRow(diagram, layout, "Bulb", 0))
        assertEquals(listOf("Behaves after", "Remove behaves after"), labels(row))
        assertEquals("glowing", ask(row, "Behaves after").initial)
        assertEquals("glowing", ask(TypeActions.at(behaving, diagram, layout, onTitle(diagram, layout, "Bulb")), "Behaves after").initial)
        assertEquals(listOf("Rename", "Set type", "Make list", "Move down", "Edit as text", "Delete"), labels(TypeActions.at(behaving, diagram, layout, onRow(diagram, layout, "Bulb", 1))))
    }

    @Test
    fun `an object offers its own entries, and the root object neither moves nor goes`() {
        val bulb = ObjectActions.at(model, objects, objectLayout, onTitle(objects, objectLayout, "wallSwitch"))
        assertEquals(
            listOf("Rename", "Edit as text", "Set type", "Add property", "Add object", "Add reference to", "Move into", "Delete"),
            labels(bulb),
        )
        assertEquals("Switch", ask(bulb, "Set type").initial)
        assertEquals(objects.nodes.map { it.id }.toSet(), assertIs<DiagramAction.Pick>(bulb.first { it.label == "Add reference to" }).candidates)

        val lamp = ObjectActions.at(model, objects, objectLayout, onTitle(objects, objectLayout, "lamp"))
        assertEquals(setOf("living room", "wallSwitch"), assertIs<DiagramAction.Pick>(lamp.first { it.label == "Move into" }).candidates)

        val root = labels(ObjectActions.at(model, objects, objectLayout, onTitle(objects, objectLayout, "living room")))
        assertEquals(listOf("Rename", "Edit as text", "Set type", "Add property", "Add object", "Add reference to"), root)
    }

    @Test
    fun `a property row of an object sets its value, adds a value and moves, and a knows row sets its target`() {
        val mode = ObjectActions.at(model, objects, objectLayout, onRow(objects, objectLayout, "lamp", 0))
        assertEquals(listOf("Set value", "Rename", "Add value", "Move down", "Edit as text", "Delete"), labels(mode))
        assertEquals("off", ask(mode, "Set value").initial)
        assertEquals("mode", ask(mode, "Rename").initial)

        val fuse = ObjectActions.at(model, objects, objectLayout, onRow(objects, objectLayout, "lamp", 1))
        assertEquals(listOf("Rename relation", "Set target", "Move up", "Edit as text", "Delete relation"), labels(fuse))
        assertEquals("fuseBox", ask(fuse, "Set target").initial)
    }

    @Test
    fun `a knows line sets its target, and a has line has none`() {
        val control = ObjectActions.at(model, objects, objectLayout, onLabel(objectLayout, "knows:lamp.control"))
        assertEquals(listOf("Rename relation", "Set target", "Edit as text", "Delete relation"), labels(control))
        assertEquals("wallSwitch", ask(control, "Set target").initial)

        val bulb = ObjectActions.at(model, objects, objectLayout, onLabel(objectLayout, "has:lamp.bulb"))
        assertEquals(listOf("Rename relation", "Edit as text", "Delete relation"), labels(bulb))
    }

    private fun labels(actions: List<DiagramAction>): List<String> = actions.map { it.label }

    private fun ask(actions: List<DiagramAction>, label: String): DiagramAction.Ask = assertIs<DiagramAction.Ask>(actions.first { it.label == label })

    /** A point in the title of the box [id] of [layout] that lies on no line and no label of [diagram]. */
    private fun onTitle(diagram: StructureDiagram, layout: StructureLayout, id: String): DiagramPoint {
        val box = layout.boxes.getValue(id)
        return free(layout, box.y + StructureMetrics.TITLE_HEIGHT / 2f, box.x, box.right).also { assertEquals(id, layout.boxAt(it), "$diagram") }
    }

    /** A point in the row [row] of the box [id] of [layout] that lies on no line and no label. */
    private fun onRow(diagram: StructureDiagram, layout: StructureLayout, id: String, row: Int): DiagramPoint {
        val box = layout.boxes.getValue(id)
        val y = box.y + StructureMetrics.TITLE_HEIGHT + StructureMetrics.ROWS_PADDING + StructureMetrics.ROW_HEIGHT * (row + 0.5f)
        return free(layout, y, box.x, box.right).also { assertEquals(row, layout.rowAt(id, it), "${diagram.node(id)}") }
    }

    /** The middle of the label of the link [id] of [layout]. */
    private fun onLabel(layout: StructureLayout, id: String): DiagramPoint = assertNotNull(layout.labels[id], id).center

    /** The first point at the height [y] between [left] and [right] of [layout] that lies on no line and no label. */
    private fun free(layout: StructureLayout, y: Float, left: Float, right: Float): DiagramPoint =
        assertNotNull(
            generateSequence(left + 2f) { it + 2f }.takeWhile { it < right - 2f }.map { DiagramPoint(it, y) }
                .firstOrNull { layout.labelAt(it) == null && layout.lineAt(it, DiagramLayout.HIT_TOLERANCE) == null },
            "no free point at $y",
        )
}
