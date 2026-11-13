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
import io.karpfen.ide.notation.diagram.statechart.StatechartIds
import io.karpfen.ide.notation.diagram.statechart.TransitionEdge
import io.karpfen.ide.notation.diagram.statechart.TransitionKind

/**
 * How a statechart is laid out. The boxes are its states, by their names. A state with nested states takes them along
 * when it is moved, and it can be sized. The lines that can be bent are those of the normal transitions, of the
 * timeouts and of the splits and joins, where each line between a state and the bar is bent on its own. Every
 * transition and every timeout has a caption that can be moved.
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

    /**
     * [vis] carried over from [before] to [after]. A state that was renamed keeps its place and its frame, and its
     * timeout keeps its waypoints and its caption. A transition whose id changed in its place in the text, because a
     * state it names was renamed or a split got one more target or a join one more source, keeps its bar, its
     * waypoints and its caption. Each line of a split or join between a state and the bar keeps its waypoints as long
     * as the transition still names that state, under its new name if it was renamed.
     */
    override fun followed(before: StatechartDiagram, after: StatechartDiagram, vis: VisLayout): VisLayout {
        val states = VisEditing.renamed(before.allStates().map { it.name }, after.allStates().map { it.name })
        val transitions = VisEditing.renamed(before.transitions.map { it.id }, after.transitions.map { it.id })
        val barLines = before.transitions.zip(after.transitions)
            .filter { (old, _) -> old.id in transitions && old.kind != TransitionKind.NORMAL }
            .flatMap { (old, new) -> barLinesFollowed(old, new, states) }
        val timeouts = states.entries.associate { (old, new) -> StatechartIds.timeout(old) to StatechartIds.timeout(new) }
        return VisEditing.rekeyed(vis, boxes = states, lines = transitions + barLines + timeouts)
    }

    /**
     * The ids of the lines of the split or join [old] between a state and its bar, each with the id of the same line
     * of [new], the same transition after a change of the text. A state that [states] says was renamed is looked
     * for under its new name. A line whose state [new] no longer names has no partner.
     */
    private fun barLinesFollowed(old: TransitionEdge, new: TransitionEdge, states: Map<String, String>): List<Pair<String, String>> {
        val from = old.sources.mapNotNull { source ->
            (states[source] ?: source).takeIf { it in new.sources }?.let { old.lineFrom(source) to new.lineFrom(it) }
        }
        val to = old.targets.mapNotNull { target ->
            (states[target] ?: target).takeIf { it in new.targets }?.let { old.lineTo(target) to new.lineTo(it) }
        }
        return from + to
    }

    override fun lines(diagram: StatechartDiagram): Set<String> = diagram.edges().flatMap { it.lineIds }.toSet()

    override fun labelled(diagram: StatechartDiagram): Set<String> = diagram.edges().map { it.id }.toSet()

    override fun placeInText(diagram: StatechartDiagram, box: String): TextRange? = diagram.state(box)?.nameRange
}
