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
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that the gestures of the canvas bend the lines of a split and of a timeout like the line of a normal
 * transition: a waypoint is added on the line, dragged and removed again, each line of a split on its own. A pause at
 * the transition the engine makes up for a timeout marks nothing.
 */
class DiagramEditingLinesTest {

    private val text = TextMeasurer.estimating()
    private val diagram = diagramOf(MACHINE)
    private val split = diagram.transitions.single { it.kind == TransitionKind.SPLIT }
    private val start = VisLayout(
        boxes = mapOf(
            "idle" to DiagramPoint(200f, 100f),
            "left" to DiagramPoint(100f, 300f),
            "right" to DiagramPoint(300f, 300f),
        ),
        bars = mapOf(split.id to DiagramPoint(200f, 200f)),
    )
    private val saved = ArrayList<VisLayout>()
    private val editing = DiagramEditing(StatechartArrangement) { saved += it }.also { it.loaded(start) }

    @Test
    fun `a line of a split gets a waypoint of its own that is dragged and removed, and the other lines stay straight`() {
        val id = split.lineTo("left")
        assertWaypointCycle(id)
    }

    @Test
    fun `the line of a timeout gets a waypoint that is dragged and removed`() {
        assertWaypointCycle("TIMEOUT:right")
    }

    @Test
    fun `a pause at the transition the engine makes up for a timeout marks nothing`() {
        val marks = StatechartPauseMarks.of(diagram, layoutOf(start), DiagramPause.AtTransition(-1, TransitionKind.NORMAL, listOf("right"), listOf("idle")))

        assertEquals(PauseMarks(emptyList(), emptyList()), marks)
    }

    /** Adds a waypoint in the middle of the line [id], drags it 40 units to the right and removes it again. */
    private fun assertWaypointCycle(id: String) {
        val line = layoutOf(start).routes.single { it.lineId == id }.points
        val onLine = DiagramPoint((line.first().x + line.last().x) / 2f, (line.first().y + line.last().y) / 2f)

        val add = assertNotNull(editing.offerAt(diagram, layoutOf(start), onLine))
        assertEquals(DiagramEditing.ADD_WAYPOINT, add.label)
        add.take()
        val added = assertNotNull(editing.vis).waypoints.getValue(id)
        assertEquals(1, added.size)
        assertEquals(setOf(id), assertNotNull(editing.vis).waypoints.keys, "only this line is bent")

        val handle = added.single()
        assertTrue(editing.startDrag(diagram, layoutOf(assertNotNull(editing.vis)), handle))
        editing.dragTo(diagram, DiagramPoint(handle.x + 40f, handle.y))
        editing.endDrag(diagram)
        val moved = DiagramPoint(handle.x + 40f, handle.y)
        assertEquals(listOf(moved), assertNotNull(editing.vis).waypoints[id])
        val bent = layoutOf(assertNotNull(editing.vis)).routes.single { it.lineId == id }
        assertEquals(moved, bent.points[1])

        val remove = assertNotNull(editing.offerAt(diagram, layoutOf(assertNotNull(editing.vis)), moved))
        assertEquals(DiagramEditing.REMOVE_WAYPOINT, remove.label)
        remove.take()
        assertNull(assertNotNull(editing.vis).waypoints[id])
        assertEquals(3, saved.size)
        assertEquals(DiagramPoint(200f, 200f), assertNotNull(editing.vis).bars[split.id], "the bar stays where it was")
    }

    private fun layoutOf(vis: VisLayout): StatechartLayout = ManualStatechartLayout.of(diagram, vis, text)

    private fun diagramOf(source: String): StatechartDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(source, 0))
        return checkNotNull(StatechartProjection.of(source, outcome.outline))
    }

    private companion object {
        val MACHINE = """
            STATEMACHINE ATTACHED TO "Job" {
                STATES {
                    INITIAL STATE "idle" { }
                    STATE "left" { }
                    STATE "right" {
                        TICK LIMIT { TIMEOUT: AFTER 4 TICKS TRANSITION TO "idle" }
                    }
                }
                TRANSITIONS {
                    SPLIT "idle" -> "left", "right" { }
                }
            }
        """.trimIndent()
    }
}
