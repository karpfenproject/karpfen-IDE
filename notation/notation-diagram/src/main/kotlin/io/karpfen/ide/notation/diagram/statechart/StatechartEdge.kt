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
package io.karpfen.ide.notation.diagram.statechart

/**
 * An edge of a [StatechartDiagram]: something that leads from states to states and is drawn as lines between them.
 * There are two kinds. A [TransitionEdge] is a transition the text declares. A [TimeoutEdge] is the timeout of a
 * state, a transition the engine takes by itself once the state has been active for a number of ticks.
 *
 * @property id identifies the edge within its state machine. No two edges share an id, also not a transition and a
 *   timeout. The caption of the edge is stored under it in a layout file.
 * @property sources the names of the states the edge leaves.
 * @property targets the names of the states the edge enters.
 * @property caption what a drawing writes beside the edge.
 * @property lineIds the ids of the lines that draw the edge, by which a layout file stores their waypoints. A normal
 *   transition and a timeout are one line with the id of the edge. A split or join is one line for each of its states.
 */
public sealed interface StatechartEdge {
    public val id: String
    public val sources: List<String>
    public val targets: List<String>
    public val caption: String
    public val lineIds: List<String>
}

/**
 * The timeout of a state as an edge of a [StatechartDiagram]: after [StateTimeout.ticks] ticks in the state [state]
 * the engine leaves it for the state [StateTimeout.target]. It is not one of the transitions of the text. It has no
 * priority, because the engine checks it before every transition of the state, and so it has no number in its
 * caption either.
 *
 * @property state the name of the state the timeout belongs to.
 * @property timeout the timeout as the tick limit of that state says it.
 */
public data class TimeoutEdge(public val state: String, public val timeout: StateTimeout) : StatechartEdge {
    override val id: String get() = StatechartIds.timeout(state)

    override val sources: List<String> get() = listOf(state)

    override val targets: List<String> get() = listOf(timeout.target)

    /** The time the state is left after, as in `after 10 ticks`, or `after 1 tick` for a single one. */
    override val caption: String get() = if (timeout.ticks == 1L) "after 1 tick" else "after ${timeout.ticks} ticks"

    override val lineIds: List<String> get() = listOf(id)
}
