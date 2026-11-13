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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TransitionKind

/**
 * How a statechart is laid out. The boxes are its states, by their names. A state with nested states takes them along
 * when it is moved, and it can be sized. The lines that can be bent are the normal transitions. The lines of a split
 * or join always run straight to their bar. Every transition has a caption that can be moved.
 */
public object StatechartArrangement : DiagramArrangement<StatechartDiagram, StatechartLayout> {

    override fun isEmpty(diagram: StatechartDiagram): Boolean = diagram.states.isEmpty()

    override fun automatic(diagram: StatechartDiagram, text: TextMeasurer): StatechartLayout = StatechartLayouter().layout(diagram, text)

    override fun byHand(diagram: StatechartDiagram, vis: VisLayout, text: TextMeasurer): StatechartLayout =
        ManualStatechartLayout.of(diagram, vis, text)

    override fun freeze(diagram: StatechartDiagram, layout: StatechartLayout): VisLayout = VisEditing.freeze(diagram, layout)

    override fun moveBox(diagram: StatechartDiagram, vis: VisLayout, box: String, delta: DiagramPoint): VisLayout =
        VisEditing.moveState(diagram, vis, box, delta)

    override fun resize(diagram: StatechartDiagram, vis: VisLayout, box: String, frame: DiagramBox): VisLayout =
        VisEditing.resize(diagram, vis, box, frame)

    override fun prune(diagram: StatechartDiagram, vis: VisLayout): VisLayout = VisEditing.prune(diagram, vis)

    override fun followed(before: StatechartDiagram, after: StatechartDiagram, vis: VisLayout): VisLayout = VisEditing.rekeyed(
        vis,
        boxes = VisEditing.renamed(before.allStates().map { it.name }, after.allStates().map { it.name }),
        lines = VisEditing.renamed(before.transitions.map { it.id }, after.transitions.map { it.id }),
    )

    override fun lines(diagram: StatechartDiagram): Set<String> =
        diagram.transitions.filter { it.kind == TransitionKind.NORMAL }.map { it.id }.toSet()

    override fun labelled(diagram: StatechartDiagram): Set<String> = diagram.transitions.map { it.id }.toSet()

    override fun placeInText(diagram: StatechartDiagram, box: String): TextRange? = diagram.state(box)?.nameRange
}
