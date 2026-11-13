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
import io.karpfen.ide.notation.diagram.layout.StatechartLayouter
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.StatechartProjection
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers what a statechart marks where a run is paused: the box of a state, or the lines, the bar and the caption of a
 * transition found by its index or, where that does not fit, by its kind and states. A place the diagram does not
 * have marks nothing.
 */
class StatechartPauseMarksTest {

    private val diagram = diagramOf(MACHINE)
    private val layout = StatechartLayouter().layout(diagram, TextMeasurer.estimating())

    @Test
    fun `a pause at a state frames the box of the state`() {
        assertEquals(PauseMarks(listOf(layout.states.getValue("wait")), emptyList()), StatechartPauseMarks.of(diagram, layout, DiagramPause.AtState("wait")))
    }

    @Test
    fun `a pause at a transition found by its index marks its line and frames its caption`() {
        val second = diagram.transitions[1]
        val route = layout.routes.single { it.edgeId == second.id }

        val marks = StatechartPauseMarks.of(diagram, layout, DiagramPause.AtTransition(1, TransitionKind.NORMAL, listOf("wait"), listOf("go")))

        assertEquals(PauseMarks(listOfNotNull(route.label), listOf(route.points)), marks)
    }

    @Test
    fun `a pause at a split marks every line of it and frames its bar and its caption`() {
        val split = diagram.transitions.single { it.kind == TransitionKind.SPLIT }
        val routes = layout.routes.filter { it.edgeId == split.id }

        val marks = StatechartPauseMarks.of(diagram, layout, DiagramPause.AtTransition(-1, TransitionKind.SPLIT, listOf("go"), listOf("b", "a")))

        assertEquals(routes.map { it.points }.toSet(), marks.lines.toSet())
        assertEquals(listOfNotNull(layout.bars[split.id], routes.firstNotNullOfOrNull { it.label }), marks.frames)
    }

    @Test
    fun `a place the diagram does not have marks nothing`() {
        val none = PauseMarks(emptyList(), emptyList())

        assertEquals(none, StatechartPauseMarks.of(diagram, layout, DiagramPause.AtState("nowhere")))
        assertEquals(none, StatechartPauseMarks.of(diagram, layout, DiagramPause.AtTransition(0, TransitionKind.JOIN, listOf("a"), listOf("go"))))
    }

    private fun diagramOf(source: String): StatechartDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES).parser.parse(TextSnapshot(source, 0))
        return checkNotNull(StatechartProjection.of(source, outcome.outline))
    }

    private companion object {
        val MACHINE = """
            STATEMACHINE ATTACHED TO "Crossing" {
                STATES {
                    INITIAL STATE "go" { }
                    STATE "wait" { }
                    STATE "a" { }
                    STATE "b" { }
                }
                TRANSITIONS {
                    TRANSITION "go" -> "wait" {
                        CONDITION { EVENT("street", "pedestrian") }
                    }
                    TRANSITION "wait" -> "go" {
                        CONDITION { EVENT("street", "clear") }
                    }
                    SPLIT "go" -> "a", "b" {
                        CONDITION { EVENT("street", "split") }
                    }
                }
            }
        """.trimIndent()
    }
}
