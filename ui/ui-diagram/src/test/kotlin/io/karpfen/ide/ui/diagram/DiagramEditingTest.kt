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
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.ManualStatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartArrangement
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
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
    fun `a secondary click on a transition adds a waypoint, a drag moves it, and a secondary click on it removes it`() {
        val id = "NORMAL:off->on#1"
        val line = automatic.routes.single { it.transitionId == id }.points
        val onLine = DiagramPoint((line[0].x + line[1].x) / 2f, (line[0].y + line[1].y) / 2f)

        assertTrue(editing.secondaryClick(diagram, automatic, onLine))
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
        assertTrue(editing.secondaryClick(diagram, layoutOf(assertNotNull(editing.vis)), DiagramPoint(handle.x + 40f, handle.y)))
        assertEquals(count - 1, assertNotNull(editing.vis).waypoints[id].orEmpty().size)
        assertFalse(editing.secondaryClick(diagram, automatic, DiagramPoint(-500f, -500f)), "nothing there")
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

    private fun layoutOf(vis: VisLayout): StatechartLayout = ManualStatechartLayout.of(diagram, vis, text)

    private fun diagramOf(source: String): StatechartDiagram {
        val snapshot = TextSnapshot(source, 0)
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(snapshot)
        return checkNotNull(StatechartProjection.of(source, outcome.outline))
    }

    private companion object {
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
