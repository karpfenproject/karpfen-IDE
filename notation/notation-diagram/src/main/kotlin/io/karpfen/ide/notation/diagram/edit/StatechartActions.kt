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

import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.Geometry
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram

/**
 * What a user can do at a point of a statechart.
 *
 * - On the line or the label of a transition: delete the transition.
 * - On a state: rename it, add a state inside it, start a transition at it, give it entry or do actions, make it the
 *   initial state, move it out of its parent or into another state, and delete it. The empty room inside a state with
 *   nested states counts as that state.
 * - On the empty canvas: add a state, add an initial state, and add a transition between two states that the user
 *   clicks one after the other.
 */
public object StatechartActions : DiagramActions<StatechartDiagram, StatechartLayout> {

    override fun at(source: String, diagram: StatechartDiagram, layout: StatechartLayout, point: DiagramPoint): List<DiagramAction> {
        val transition = layout.labelAt(point) ?: lineAt(layout, point)
        val state = layout.stateAt(point)?.let(diagram::state)
        return when {
            transition != null -> listOf(
                DiagramAction.Change("Delete transition") { StatechartEdits.deleteTransition(source, diagram, transition) },
            )
            state != null -> onState(source, diagram, layout, state, point)
            else -> onCanvas(source, diagram, point)
        }
    }

    private fun onState(source: String, diagram: StatechartDiagram, layout: StatechartLayout, state: StateNode, point: DiagramPoint): List<DiagramAction> {
        val name = state.name
        val parent = diagram.parentOf(name)
        val all = diagram.allStates().map { it.name }.toSet()
        val newParents = all - state.withDescendants().map { it.name }.toSet() - setOfNotNull(parent?.name)
        return listOfNotNull(
            DiagramAction.Ask("Rename", name) { new -> DiagramAction.Change("Rename") { StatechartEdits.rename(source, diagram, name, new) } },
            DiagramAction.Change("Add inner state") {
                val frame = if (state.isComposite) null else layout.states[name]
                StatechartEdits.addState(source, diagram, name, StatechartEdits.freshName(diagram), initial = false, at = point, parentFrame = frame)
            },
            DiagramAction.Pick("Add transition from here", "Click the state the transition leads to.", all) { target ->
                DiagramAction.Change("Add transition") { StatechartEdits.addTransition(source, diagram, name, target) }
            },
            DiagramAction.Change("Add entry actions") { StatechartEdits.addBlock(source, diagram, name, StatechartEdits.ENTRY) }.takeIf { !state.hasEntry },
            DiagramAction.Change("Add do actions") { StatechartEdits.addBlock(source, diagram, name, StatechartEdits.DO) }.takeIf { !state.hasDo },
            DiagramAction.Change("Make initial state") { StatechartEdits.makeInitial(source, diagram, name) }.takeIf { !state.isInitial },
            DiagramAction.Change("Make outer state") {
                StatechartEdits.moveTo(source, diagram, name, parent?.let { diagram.parentOf(it.name) }?.name)
            }.takeIf { parent != null },
            DiagramAction.Pick("Make inner state of", "Click the state that becomes the outer state.", newParents) { outer ->
                DiagramAction.Change("Make inner state") { StatechartEdits.moveTo(source, diagram, name, outer) }
            }.takeIf { newParents.isNotEmpty() },
            DiagramAction.Change("Delete state") { StatechartEdits.deleteState(source, diagram, name) },
        )
    }

    private fun onCanvas(source: String, diagram: StatechartDiagram, point: DiagramPoint): List<DiagramAction> {
        val all = diagram.allStates().map { it.name }.toSet()
        return listOfNotNull(
            DiagramAction.Change("Add state") {
                StatechartEdits.addState(source, diagram, null, StatechartEdits.freshName(diagram), initial = false, at = point)
            },
            DiagramAction.Change("Add initial state") {
                StatechartEdits.addState(source, diagram, null, StatechartEdits.freshName(diagram), initial = true, at = point)
            }.takeIf { diagram.states.none { it.isInitial } },
            DiagramAction.Pick("Add transition", "Click the state the transition starts at.", all) { start ->
                DiagramAction.Pick("Add transition", "Click the state the transition leads to.", all) { target ->
                    DiagramAction.Change("Add transition") { StatechartEdits.addTransition(source, diagram, start, target) }
                }
            }.takeIf { all.isNotEmpty() },
        )
    }

    /** The id of the transition one of whose lines runs at [point], or null. */
    private fun lineAt(layout: StatechartLayout, point: DiagramPoint): String? =
        layout.routes
            .filter { it.transitionId != null }
            .map { it to Geometry.distanceToLine(point, it.points) }
            .filter { it.second <= DiagramLayout.HIT_TOLERANCE }
            .minByOrNull { it.second }
            ?.first?.transitionId
}
