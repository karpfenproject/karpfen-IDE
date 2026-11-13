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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.notation.diagram.LampStateMachine
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context menu of a statechart offers the entries that write a construct of an optional language feature only
 * while that feature is switched on. Entries that remove a history or a breakpoint stay, so an error can be fixed in
 * the diagram.
 */
class StatechartActionsTest {

    private val text = """
        STATEMACHINE ATTACHED TO "Job" {
            STATES {
                INITIAL STATE "idle" { }
                DEEP HISTORY STATE "busy" {
                    BREAKPOINT { ENTRY }
                    STATE "left" { }
                    STATE "right" { }
                }
                STATE "done" { }
            }
            TRANSITIONS {
                SPLIT "idle" -> "left", "right" { }
                JOIN "left", "right" -> "done" { BREAKPOINT { END } }
            }
            MACROS {
                MACRO "half" { RETURNS("number") DEFINITION { EVAL { return 1 } } }
            }
        }
    """.trimIndent()

    private val diagram = LampStateMachine.diagramOf(text)
    private val layout = StatechartLayouter().layout(diagram, TextMeasurer.estimating())

    /** The labels of the entries at [point] while [features] are on. */
    private fun labels(features: Set<KstatesFeature>, point: DiagramPoint): List<String> =
        StatechartActions(features).at(text, diagram, layout, point).map { it.label }

    /** A point inside the state [name] that lies on no line. */
    private fun inState(name: String): DiagramPoint = layout.states.getValue(name).let { DiagramPoint(it.x + 2f, it.y + 2f) }

    /** A point on the line of the transition [id]. */
    private fun onTransition(id: String): DiagramPoint = layout.routes.first { it.transitionId == id }.points.first()

    /** A point on the empty canvas. */
    private val canvas = DiagramPoint(-500f, -500f)

    @Test
    fun `with every feature on, the entries of every feature are offered`() {
        val all = KstatesFeature.entries.toSet()

        assertTrue("Add tick limit" in labels(all, inState("done")))
        assertTrue("Remember the last inner state (shallow history)" in labels(all, inState("busy")))
        assertTrue("Add breakpoint at exit" in labels(all, inState("busy")))
        assertTrue(listOf("Add split", "Add join", "Edit macros").all { it in labels(all, canvas) }, "${labels(all, canvas)}")
        val split = diagram.transitions.first { it.sources == listOf("idle") }.id
        assertTrue("Add breakpoint at start" in labels(all, onTransition(split)))
    }

    @Test
    fun `with every feature off, nothing is added, and set histories and breakpoints can still be removed`() {
        val busy = labels(emptySet(), inState("busy"))
        val done = labels(emptySet(), inState("done"))
        val canvasLabels = labels(emptySet(), canvas)
        val join = diagram.transitions.first { it.targets == listOf("done") }.id
        val onJoin = labels(emptySet(), onTransition(join))

        assertTrue(busy.none { it.startsWith("Remember") || it == "Add breakpoint at exit" }, "$busy")
        assertTrue("Remove history" in busy && "Remove breakpoint at entry" in busy, "$busy")
        assertTrue(done.none { it == "Add tick limit" || it.startsWith("Add breakpoint") }, "$done")
        assertTrue(canvasLabels.none { it in listOf("Add split", "Add join", "Edit macros") }, "$canvasLabels")
        assertTrue("Add transition" in canvasLabels)
        assertEquals(listOf("Remove breakpoint at end"), onJoin.filter { "breakpoint" in it }, "$onJoin")
        assertTrue("Add source" !in onJoin)
    }

    @Test
    fun `a single switched-on feature brings back only its own entries`() {
        val parallel = setOf(KstatesFeature.PARALLEL)
        val join = diagram.transitions.first { it.targets == listOf("done") }.id

        assertEquals(listOf("Add split", "Add join"), labels(parallel, canvas).filter { it in listOf("Add split", "Add join", "Edit macros") })
        assertEquals(listOf("Edit macros"), labels(setOf(KstatesFeature.MACROS), canvas).filter { it in listOf("Add split", "Add join", "Edit macros") })
        assertTrue("Add tick limit" in labels(setOf(KstatesFeature.TICK_LIMIT), inState("done")))
        assertTrue("Add tick limit" !in labels(parallel, inState("done")))
        assertTrue(labels(parallel, onTransition(join)).none { it == "Add breakpoint at start" })
    }
}
