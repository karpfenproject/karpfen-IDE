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
package io.karpfen.ide.ui.diagram

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.edit.StatechartEdits
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.ManualStatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartArrangement
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.StructureArrangement
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import io.karpfen.ide.notation.diagram.structure.MetamodelProjection
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests how the gestures of the canvas change the layout by hand and when it is saved. */
class DiagramEditingTest {

    private val text = TextMeasurer.estimating()
    private val diagram = diagramOf(MACHINE)
    private val automatic = StatechartLayouter().layout(diagram, text)
    private val saved = ArrayList<VisLayout>()
    private val editing = DiagramEditing(StatechartArrangement) { saved += it }

    @Test
    fun `dragging a state freezes the automatic layout, moves the state on the grid and saves once at the end`() {
        val off = automatic.states.getValue("off").center

        assertTrue(editing.startDrag(diagram, automatic, off))
        assertTrue(editing.isDragging)
        editing.dragTo(diagram, DiagramPoint(off.x + 43f, off.y + 18f))
        editing.dragTo(diagram, DiagramPoint(off.x + 61f, off.y + 18f))
        assertEquals(emptyList(), saved, "nothing is saved while the pointer moves")
        editing.endDrag(diagram)

        assertFalse(editing.isDragging)
        val vis = assertNotNull(editing.vis)
        assertEquals(listOf(vis), saved)
        val before = automatic.states.getValue("off").center
        val after = layoutOf(vis).states.getValue("off").center
        assertEquals(60f to 20f, (after.x - before.x).let { Math.round(it / 10f) * 10f } to (after.y - before.y).let { Math.round(it / 10f) * 10f })
        assertEquals(automatic.states.keys, layoutOf(vis).states.keys)
    }

    @Test
    fun `a drag that moves nothing keeps the automatic layout and saves nothing`() {
        val off = automatic.states.getValue("off").center

        editing.startDrag(diagram, automatic, off)
        editing.dragTo(diagram, DiagramPoint(off.x + 2f, off.y + 1f))
        editing.endDrag(diagram)

        assertNull(editing.vis)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `a drag on the empty canvas grabs nothing`() {
        assertFalse(editing.startDrag(diagram, automatic, DiagramPoint(-500f, -500f)))
        assertFalse(editing.isDragging)
    }

    @Test
    fun `a secondary click offers to add a waypoint to a transition and to remove one, and changes nothing by itself`() {
        val id = "NORMAL:off->on#1"
        val line = automatic.routes.single { it.edgeId == id }.points
        val onLine = DiagramPoint((line[0].x + line[1].x) / 2f, (line[0].y + line[1].y) / 2f)

        val add = assertNotNull(editing.offerAt(diagram, automatic, onLine))
        assertEquals(DiagramEditing.ADD_WAYPOINT, add.label)
        assertNull(editing.vis, "the offer alone changes nothing")
        assertEquals(emptyList(), saved)

        add.take()
        val added = assertNotNull(editing.vis).waypoints.getValue(id)
        assertEquals(1, saved.size)

        val layout = layoutOf(assertNotNull(editing.vis))
        val handle = added.minBy { (it.x - onLine.x) * (it.x - onLine.x) + (it.y - onLine.y) * (it.y - onLine.y) }
        assertTrue(editing.startDrag(diagram, layout, handle))
        editing.dragTo(diagram, DiagramPoint(handle.x + 40f, handle.y))
        editing.endDrag(diagram)
        val moved = assertNotNull(editing.vis).waypoints.getValue(id)
        assertTrue(DiagramPoint(handle.x + 40f, handle.y) in moved)
        assertEquals(2, saved.size)

        val count = moved.size
        val remove = assertNotNull(editing.offerAt(diagram, layoutOf(assertNotNull(editing.vis)), DiagramPoint(handle.x + 40f, handle.y)))
        assertEquals(DiagramEditing.REMOVE_WAYPOINT, remove.label)
        assertEquals(count, assertNotNull(editing.vis).waypoints[id].orEmpty().size, "the offer alone removes nothing")
        remove.take()
        assertEquals(count - 1, assertNotNull(editing.vis).waypoints[id].orEmpty().size)
        assertNull(editing.offerAt(diagram, automatic, DiagramPoint(-500f, -500f)), "nothing there")
    }

    @Test
    fun `dragging the label of a transition moves the label and nothing else`() {
        val id = "NORMAL:off->on#1"
        val label = assertNotNull(automatic.routes.single { it.edgeId == id }.label)

        assertTrue(editing.startDrag(diagram, automatic, label.center))
        editing.dragTo(diagram, DiagramPoint(label.center.x + 31f, label.center.y))
        editing.endDrag(diagram)

        val vis = assertNotNull(editing.vis)
        val moved = assertNotNull(layoutOf(vis).labels[id])
        assertTrue(kotlin.math.abs(moved.center.x - label.center.x - 30f) <= 10f && kotlin.math.abs(moved.center.y - label.center.y) <= 10f, "$label became $moved")
        assertEquals(listOf(vis), saved)
        assertEquals(automatic.states.keys, layoutOf(vis).states.keys)
    }

    @Test
    fun `dragging the border of a state with inner states sizes it`() {
        val nested = diagramOf(NESTED)
        val layout = StatechartLayouter().layout(nested, text)
        val outer = layout.states.getValue("outer")
        val editing = DiagramEditing(StatechartArrangement) { saved += it }

        assertTrue(editing.startDrag(nested, layout, DiagramPoint(outer.right, outer.bottom)))
        editing.dragTo(nested, DiagramPoint(outer.right + 80f, outer.bottom + 40f))
        editing.endDrag(nested)

        val sized = ManualStatechartLayout.of(nested, assertNotNull(editing.vis), text).states.getValue("outer")
        assertTrue(sized.right >= outer.right + 70f && sized.bottom >= outer.bottom + 30f, "$outer became $sized")
        assertEquals(1, saved.size)
    }

    @Test
    fun `a change that places a new state fixes the layout and is saved once the new diagram is there`() {
        val change = assertNotNull(StatechartEdits.addState(MACHINE, diagram, null, "idle", initial = false, at = DiagramPoint(300f, 200f)))
        val after = diagramOf(MACHINE.replace("STATE \"on\" { }", "STATE \"on\" { }\n        STATE \"idle\" { }"))

        editing.changed(diagram, automatic, change)
        assertEquals(emptyList(), saved, "the diagram of the new text is not there yet")
        editing.diagramChanged(after)

        val vis = saved.single()
        assertEquals(DiagramPoint(300f, 200f), vis.boxes["idle"])
        assertEquals(setOf("off", "on", "idle"), vis.boxes.keys)
        editing.diagramChanged(after)
        assertEquals(1, saved.size, "it is saved once")
    }

    @Test
    fun `a state renamed in the text keeps its place in a layout by hand, and the layout is saved`() {
        val off = automatic.states.getValue("off").center
        editing.follow(diagram)
        editing.startDrag(diagram, automatic, off)
        editing.dragTo(diagram, DiagramPoint(off.x + 50f, off.y))
        editing.endDrag(diagram)
        val place = assertNotNull(editing.vis).boxes.getValue("off")
        val renamed = diagramOf(MACHINE.replace("STATE \"off\"", "STATE \"dark\""))

        editing.follow(renamed)
        editing.follow(renamed)

        assertEquals(place, assertNotNull(editing.vis).boxes["dark"])
        assertNull(assertNotNull(editing.vis).boxes["off"])
        assertEquals(1, saved.size, "following alone saves nothing")
        editing.diagramChanged(renamed)
        assertEquals(place, saved.last().boxes["dark"])
        assertEquals(2, saved.size)
    }

    @Test
    fun `an automatic layout has nothing to carry over`() {
        editing.follow(diagram)
        editing.follow(diagramOf(MACHINE.replace("STATE \"off\"", "STATE \"dark\"")))

        assertNull(editing.vis)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `a change that only renames leaves an automatic layout automatic`() {
        val change = assertNotNull(StatechartEdits.rename(MACHINE, diagram, "on", "lit"))

        editing.changed(diagram, automatic, change)
        editing.diagramChanged(diagram)

        assertNull(editing.vis)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `what is read from the file is taken in, but never over a change that is still being written`() {
        val fromFile = VisLayout(boxes = mapOf("off" to DiagramPoint(10f, 10f), "on" to DiagramPoint(200f, 10f)))
        editing.loaded(fromFile)
        assertEquals(fromFile, editing.vis)

        val off = layoutOf(fromFile).states.getValue("off").center
        editing.startDrag(diagram, layoutOf(fromFile), off)
        editing.loaded(null)
        assertNotNull(editing.vis, "a reload during a drag is ignored")
        editing.dragTo(diagram, DiagramPoint(off.x + 50f, off.y))
        editing.endDrag(diagram)
        val mine = assertNotNull(editing.vis)

        editing.loaded(fromFile)
        assertEquals(mine, editing.vis, "the older file content does not undo the change")
        editing.loaded(mine)
        editing.loaded(null)
        assertNull(editing.vis, "once the change is on disk, the file counts again, also when it was deleted")
    }

    @Test
    fun `a state that is new after the layout by hand can be dragged like any other, and nothing else moves`() {
        val grown = diagramOf(GROWN)
        editing.loaded(STORED)
        val before = ManualStatechartLayout.of(grown, STORED, text)
        val idle = before.states.getValue("idle").center

        assertTrue(editing.startDrag(grown, before, idle))
        editing.dragTo(grown, DiagramPoint(idle.x + 41f, idle.y + 19f))
        editing.endDrag(grown)

        val vis = saved.single()
        assertEquals(DiagramPoint(idle.x + 40f, idle.y + 20f), vis.boxes["idle"])
        val after = ManualStatechartLayout.of(grown, vis, text)
        assertEquals(DiagramPoint(idle.x + 40f, idle.y + 20f), after.states.getValue("idle").center)
        assertEquals(before.states - "idle", after.states - "idle", "nothing else moves")
    }

    @Test
    fun `a state that is new after the layout by hand stays where it is drawn while a stored state is dragged`() {
        val grown = diagramOf(GROWN)
        editing.loaded(STORED)
        val before = ManualStatechartLayout.of(grown, STORED, text)
        val on = before.states.getValue("on").center

        assertTrue(editing.startDrag(grown, before, on))
        editing.dragTo(grown, DiagramPoint(on.x + 100f, on.y + 60f))
        assertEquals(before.states.getValue("idle"), ManualStatechartLayout.of(grown, assertNotNull(editing.vis), text).states.getValue("idle"))
        editing.endDrag(grown)

        val vis = saved.single()
        assertEquals(before.states.getValue("idle").center, vis.boxes["idle"], "the saved layout holds the new state")
        assertEquals(before.states.getValue("idle"), ManualStatechartLayout.of(grown, vis, text).states.getValue("idle"))
    }

    @Test
    fun `a drag of a new state that moves nothing leaves the layout as it was and saves nothing`() {
        val grown = diagramOf(GROWN)
        editing.loaded(STORED)
        val idle = ManualStatechartLayout.of(grown, STORED, text).states.getValue("idle").center

        editing.startDrag(grown, ManualStatechartLayout.of(grown, STORED, text), idle)
        editing.dragTo(grown, DiagramPoint(idle.x + 2f, idle.y + 1f))
        editing.endDrag(grown)

        assertEquals(STORED, editing.vis)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `a change that places a state right of a new one leaves the new one where it is drawn and saves its place`() {
        val grown = diagramOf(GROWN)
        editing.loaded(STORED)
        val before = ManualStatechartLayout.of(grown, STORED, text)
        val idle = before.states.getValue("idle")
        val farRight = DiagramPoint(idle.right + 200f, idle.center.y)
        val change = assertNotNull(StatechartEdits.addState(GROWN, grown, null, "extra", initial = false, at = farRight))
        val after = diagramOf(GROWN.replace("STATE \"idle\" { }", "STATE \"idle\" { }\n        STATE \"extra\" { }"))

        editing.changed(grown, before, change)
        editing.diagramChanged(after)

        val vis = saved.single()
        assertEquals(idle, ManualStatechartLayout.of(after, vis, text).states.getValue("idle"), "the new state does not jump")
        assertEquals(idle.center, vis.boxes["idle"], "the saved layout holds the new state")
    }

    @Test
    fun `adding a waypoint while a new state has no place saves the place where the state is drawn`() {
        val grown = diagramOf(GROWN)
        editing.loaded(STORED)
        val before = ManualStatechartLayout.of(grown, STORED, text)
        val line = before.routes.single { it.edgeId == "NORMAL:off->on#1" }.points
        val onLine = DiagramPoint((line[0].x + line[1].x) / 2f, (line[0].y + line[1].y) / 2f)

        val add = assertNotNull(editing.offerAt(grown, before, onLine))
        assertEquals(DiagramEditing.ADD_WAYPOINT, add.label)
        add.take()

        val vis = saved.single()
        assertEquals(before.states.getValue("idle").center, vis.boxes["idle"], "the saved layout holds the new state")
        assertEquals(before.states.getValue("idle"), ManualStatechartLayout.of(grown, vis, text).states.getValue("idle"))
    }

    @Test
    fun `removing a waypoint while a new state has no place saves the place where the state is drawn`() {
        val grown = diagramOf(GROWN)
        val bent = STORED.copy(waypoints = mapOf("NORMAL:off->on#1" to listOf(DiagramPoint(200f, 160f))))
        editing.loaded(bent)
        val before = ManualStatechartLayout.of(grown, bent, text)

        val remove = assertNotNull(editing.offerAt(grown, before, DiagramPoint(200f, 160f)))
        assertEquals(DiagramEditing.REMOVE_WAYPOINT, remove.label)
        remove.take()

        val vis = saved.single()
        assertEquals(emptyMap(), vis.waypoints)
        assertEquals(before.states.getValue("idle").center, vis.boxes["idle"], "the saved layout holds the new state")
    }

    @Test
    fun `a type that is new after the layout by hand can be dragged like any other, and nothing else moves`() {
        val types = typesOf(TYPES)
        val editing = DiagramEditing(StructureArrangement) { saved += it }
        editing.loaded(STORED_TYPES)
        val before = StructureArrangement.byHand(types, STORED_TYPES, text)
        val switch = before.boxes.getValue("Switch").center

        assertTrue(editing.startDrag(types, before, switch))
        editing.dragTo(types, DiagramPoint(switch.x - 29f, switch.y + 52f))
        editing.endDrag(types)

        val vis = saved.single()
        assertEquals(DiagramPoint(switch.x - 30f, switch.y + 50f), vis.boxes["Switch"])
        val after = StructureArrangement.byHand(types, vis, text)
        assertEquals(DiagramPoint(switch.x - 30f, switch.y + 50f), after.boxes.getValue("Switch").center)
        assertEquals(before.boxes - "Switch", after.boxes - "Switch", "nothing else moves")
    }

    @Test
    fun `a type that is new after the layout by hand stays where it is drawn while a stored type is dragged`() {
        val types = typesOf(TYPES)
        val editing = DiagramEditing(StructureArrangement) { saved += it }
        editing.loaded(STORED_TYPES)
        val before = StructureArrangement.byHand(types, STORED_TYPES, text)
        val lamp = before.boxes.getValue("Lamp").center

        assertTrue(editing.startDrag(types, before, lamp))
        editing.dragTo(types, DiagramPoint(lamp.x + 200f, lamp.y - 40f))
        editing.endDrag(types)

        val vis = saved.single()
        assertEquals(before.boxes.getValue("Switch").center, vis.boxes["Switch"], "the saved layout holds the new type")
        assertEquals(before.boxes.getValue("Switch"), StructureArrangement.byHand(types, vis, text).boxes.getValue("Switch"))
    }

    private fun layoutOf(vis: VisLayout): StatechartLayout = ManualStatechartLayout.of(diagram, vis, text)

    private fun typesOf(source: String): StructureDiagram =
        MetamodelProjection.of(defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser.parse(TextSnapshot(source, 0)).outline, source)

    private fun diagramOf(source: String): StatechartDiagram {
        val snapshot = TextSnapshot(source, 0)
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(snapshot)
        return checkNotNull(StatechartProjection.of(source, outcome.outline))
    }

    private companion object {
        /** [MACHINE] with the state "idle", which is new in the text after [STORED] was made. */
        val GROWN = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" { }
                    STATE "idle" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                }
            }
        """.trimIndent()

        /** A layout by hand of [MACHINE], which has no place for "idle". */
        val STORED = VisLayout(boxes = mapOf("off" to DiagramPoint(100f, 100f), "on" to DiagramPoint(300f, 100f)))

        /** Two types and a third, "Switch", that is new in the text after [STORED_TYPES] was made. */
        val TYPES = """
            type "Switch" "A wall switch" {
                prop("presses", "number")
            }
            type "Lamp" "A lamp" {
                knows("control", "Switch")
            }
            type "Room" "A room" {
                has("lamps", list("Lamp"))
            }
        """.trimIndent()

        /** A layout by hand of [TYPES] before "Switch" was added. */
        val STORED_TYPES = VisLayout(boxes = mapOf("Lamp" to DiagramPoint(100f, 300f), "Room" to DiagramPoint(100f, 100f)))

        val NESTED = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "outer" {
                        STATE "inner" { }
                    }
                }
                TRANSITIONS { }
            }
        """.trimIndent()

        val MACHINE = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    STATE "on" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "on" { }
                }
            }
        """.trimIndent()
    }
}
