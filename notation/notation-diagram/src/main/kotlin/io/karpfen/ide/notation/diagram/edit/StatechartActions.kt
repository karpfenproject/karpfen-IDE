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
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StatechartLayout
import io.karpfen.ide.notation.diagram.statechart.StateHistory
import io.karpfen.ide.notation.diagram.statechart.StateNode
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.statechart.TimeoutEdge
import io.karpfen.ide.notation.diagram.statechart.TransitionEdge
import io.karpfen.ide.notation.diagram.statechart.TransitionKind

/**
 * What a user can do at a point of a statechart.
 *
 * - On the line or the label of a transition: edit it as text, give a split one more target or a join one more
 *   source, move it earlier or later in the order that is its priority, mark it as not looping, set and remove its
 *   breakpoints, and delete it.
 * - On the line or the label of a timeout: edit the tick limit of its state as text, in the same cell its marker
 *   opens, let it lead to another state, and remove it. A timeout is no transition of the text, so it has no
 *   priority, no looping mark and no breakpoints.
 * - On a state: rename it, edit it as text, add a state inside it, start a transition at it, give it entry or do
 *   actions, make it the initial state, give a state with nested states a history or take it away, give it a tick
 *   limit, set and remove its breakpoints, move it out of its parent or into another state, and delete it. The empty
 *   room inside a state with nested states counts as that state.
 * - On the empty canvas: add a state, add an initial state, add a transition, a split or a join between states that
 *   the user clicks one after the other, and edit the macros as text.
 *
 * The entries that write a construct of an optional language feature are offered only while that feature is among
 * [switchedOn]: giving a state a history, a tick limit or a breakpoint, giving a transition a breakpoint, adding a
 * split, a join, a target or a source, letting a timeout lead to another state, and editing the macros. An entry
 * that removes a history, a breakpoint or a timeout stays, so a construct that is an error while its feature is off
 * can still be taken away. What the diagram shows of existing constructs does not depend on the features.
 *
 * @param switchedOn the optional language features of KStates that are switched on for the project.
 */
public class StatechartActions(private val switchedOn: Set<KstatesFeature>) : DiagramActions<StatechartDiagram, StatechartLayout> {

    override fun at(source: String, diagram: StatechartDiagram, layout: StatechartLayout, point: DiagramPoint): List<DiagramAction> {
        val edge = (layout.labelAt(point) ?: layout.routeAt(point)?.edgeId)?.let(diagram::edge)
        val state = layout.stateAt(point)?.let(diagram::state)
        return when {
            edge is TransitionEdge -> onTransition(source, diagram, edge)
            edge is TimeoutEdge -> onTimeout(source, diagram, edge)
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
            DiagramAction.Open("Edit as text", ConstructCells.of(name, "State $name", source, state.range)),
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
            DiagramAction.Change("Remember the last inner state (shallow history)") {
                StatechartEdits.setHistory(source, diagram, name, StateHistory.SHALLOW)
            }.takeIf { state.isComposite && state.history != StateHistory.SHALLOW && KstatesFeature.HISTORY.isOn },
            DiagramAction.Change("Remember the last innermost state (deep history)") {
                StatechartEdits.setHistory(source, diagram, name, StateHistory.DEEP)
            }.takeIf { state.isComposite && state.history != StateHistory.DEEP && KstatesFeature.HISTORY.isOn },
            DiagramAction.Change("Remove history") {
                StatechartEdits.setHistory(source, diagram, name, StateHistory.NONE)
            }.takeIf { state.history != StateHistory.NONE },
            DiagramAction.Change("Add tick limit") {
                StatechartEdits.addTickLimit(source, diagram, name)
            }.takeIf { !state.hasTickLimit && KstatesFeature.TICK_LIMIT.isOn },
            DiagramAction.Change(if (state.entryBreakpoint) "Remove breakpoint at entry" else "Add breakpoint at entry") {
                StatechartEdits.setStateBreakpoints(source, diagram, name, !state.entryBreakpoint, state.exitBreakpoint)
            }.takeIf { state.entryBreakpoint || KstatesFeature.BREAKPOINTS.isOn },
            DiagramAction.Change(if (state.exitBreakpoint) "Remove breakpoint at exit" else "Add breakpoint at exit") {
                StatechartEdits.setStateBreakpoints(source, diagram, name, state.entryBreakpoint, !state.exitBreakpoint)
            }.takeIf { state.exitBreakpoint || KstatesFeature.BREAKPOINTS.isOn },
            DiagramAction.Change("Make outer state") {
                StatechartEdits.moveTo(source, diagram, name, parent?.let { diagram.parentOf(it.name) }?.name)
            }.takeIf { parent != null },
            DiagramAction.Pick("Make inner state of", "Click the state that becomes the outer state.", newParents) { outer ->
                DiagramAction.Change("Make inner state") { StatechartEdits.moveTo(source, diagram, name, outer) }
            }.takeIf { newParents.isNotEmpty() },
            DiagramAction.Change("Delete state") { StatechartEdits.deleteState(source, diagram, name) },
        )
    }

    private fun onTransition(source: String, diagram: StatechartDiagram, transition: TransitionEdge): List<DiagramAction> {
        val id = transition.id
        val all = diagram.allStates().map { it.name }.toSet()
        val free = all - (transition.sources + transition.targets).toSet()
        return listOfNotNull(
            DiagramAction.Open("Edit as text", ConstructCells.of(id, "Transition ${transition.caption}", source, transition.range)),
            DiagramAction.Pick("Add target", "Click the state the split also leads to.", free) { target ->
                DiagramAction.Change("Add target") { StatechartEdits.addEnd(source, diagram, id, target) }
            }.takeIf { transition.kind == TransitionKind.SPLIT && free.isNotEmpty() && KstatesFeature.PARALLEL.isOn },
            DiagramAction.Pick("Add source", "Click the state the join also starts at.", free) { start ->
                DiagramAction.Change("Add source") { StatechartEdits.addEnd(source, diagram, id, start) }
            }.takeIf { transition.kind == TransitionKind.JOIN && free.isNotEmpty() && KstatesFeature.PARALLEL.isOn },
            DiagramAction.Change("Move earlier, to priority ${transition.priority - 1}") {
                StatechartEdits.move(source, diagram, id, earlier = true)
            }.takeIf { transition.priority > 1 },
            DiagramAction.Change("Move later, to priority ${transition.priority + 1}") {
                StatechartEdits.move(source, diagram, id, earlier = false)
            }.takeIf { transition.priority < diagram.transitions.size },
            DiagramAction.Change(if (transition.notLooping) "Allow looping" else "Mark as not looping") {
                StatechartEdits.toggleLooping(source, diagram, id)
            },
            DiagramAction.Change(if (transition.startBreakpoint) "Remove breakpoint at start" else "Add breakpoint at start") {
                StatechartEdits.setTransitionBreakpoints(source, diagram, id, !transition.startBreakpoint, transition.endBreakpoint)
            }.takeIf { transition.startBreakpoint || KstatesFeature.BREAKPOINTS.isOn },
            DiagramAction.Change(if (transition.endBreakpoint) "Remove breakpoint at end" else "Add breakpoint at end") {
                StatechartEdits.setTransitionBreakpoints(source, diagram, id, transition.startBreakpoint, !transition.endBreakpoint)
            }.takeIf { transition.endBreakpoint || KstatesFeature.BREAKPOINTS.isOn },
            DiagramAction.Change("Delete transition") { StatechartEdits.deleteTransition(source, diagram, id) },
        )
    }

    private fun onTimeout(source: String, diagram: StatechartDiagram, timeout: TimeoutEdge): List<DiagramAction> {
        val state = diagram.state(timeout.state) ?: return emptyList()
        val targets = diagram.allStates().map { it.name }.toSet() - timeout.timeout.target
        return listOfNotNull(
            StatechartCells.tickLimitOf(source, state)?.let { DiagramAction.Open("Edit tick limit as text", it) },
            DiagramAction.Pick("Lead to another state", "Click the state the timeout leads to.", targets) { target ->
                DiagramAction.Change("Lead to another state") { TimeoutEdits.retarget(source, diagram, timeout.state, target) }
            }.takeIf { targets.isNotEmpty() && KstatesFeature.TICK_LIMIT.isOn },
            DiagramAction.Change("Remove timeout") { TimeoutEdits.remove(source, diagram, timeout.state) },
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
            DiagramAction.Pick("Add split", "Click the state the split starts at.", all) { start ->
                DiagramAction.Pick("Add split", "Click the first state the split leads to.", all - start) { first ->
                    DiagramAction.Pick("Add split", "Click the second state the split leads to.", all - start - first) { second ->
                        DiagramAction.Change("Add split") { StatechartEdits.addSplit(source, diagram, start, listOf(first, second)) }
                    }
                }
            }.takeIf { all.size >= 3 && KstatesFeature.PARALLEL.isOn },
            DiagramAction.Pick("Add join", "Click the first state the join starts at.", all) { first ->
                DiagramAction.Pick("Add join", "Click the second state the join starts at.", all - first) { second ->
                    DiagramAction.Pick("Add join", "Click the state the join leads to.", all - first - second) { target ->
                        DiagramAction.Change("Add join") { StatechartEdits.addJoin(source, diagram, listOf(first, second), target) }
                    }
                }
            }.takeIf { all.size >= 3 && KstatesFeature.PARALLEL.isOn },
            StatechartEdits.macros(source, diagram)?.takeIf { KstatesFeature.MACROS.isOn }?.let { DiagramAction.Open("Edit macros", it) },
        )
    }

    /** True if this feature is among [switchedOn]. */
    private val KstatesFeature.isOn: Boolean
        get() = this in switchedOn
}
