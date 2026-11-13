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

import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram

/**
 * What a drawing marks to show where a run is paused. Immutable.
 *
 * @property frames the boxes that get a frame: a state, or the caption and the bar of a transition.
 * @property lines the lines that are drawn wide: those of a transition.
 */
internal data class PauseMarks(val frames: List<DiagramBox>, val lines: List<List<DiagramPoint>>)

/**
 * Finds what a statechart marks for a pause. A pause at a state marks the box of the state. A pause at a transition
 * marks every line of the transition, its bar if it is a split or a join, and the box of its caption. The transition
 * is found by [StatechartDiagram.transitionOf]. A place the diagram or its layout does not show marks nothing.
 * Stateless.
 */
internal object StatechartPauseMarks {

    /** What [diagram] laid out as [layout] marks for [pause]. */
    fun of(diagram: StatechartDiagram, layout: StatechartLayout, pause: DiagramPause): PauseMarks = when (pause) {
        is DiagramPause.AtState -> PauseMarks(listOfNotNull(layout.states[pause.state]), emptyList())
        is DiagramPause.AtTransition -> {
            val transition = diagram.transitionOf(pause.index, pause.kind, pause.sources, pause.targets)
            val routes = transition?.let { found -> layout.routes.filter { it.transitionId == found.id } }.orEmpty()
            val frames = listOfNotNull(transition?.let { layout.bars[it.id] }, routes.firstNotNullOfOrNull { it.label })
            PauseMarks(frames, routes.map { it.points })
        }
    }
}
