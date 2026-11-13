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

import io.karpfen.ide.notation.diagram.statechart.TransitionKind

/**
 * What a running model adds to the diagram of one of its files: the states its state machine is in, and the place
 * where a debugger paused it. A statechart marks both. The other diagrams show nothing of a run.
 *
 * @property active the ids of the boxes that are active now. In a statechart these are the names of the active
 *   states, the outer ones included.
 * @property pause where the run is paused at a breakpoint of the state machine, or null while it is not.
 */
public data class DiagramLive(public val active: Set<String> = emptySet(), public val pause: DiagramPause? = null)

/** Where a run is paused at a breakpoint of a state machine, as its statechart marks it. Immutable. */
public sealed interface DiagramPause {
    /**
     * At the entry or the exit of a state.
     *
     * @property state the name of the state.
     */
    public data class AtState(public val state: String) : DiagramPause

    /**
     * At the start or the end of a transition. The statechart finds it as `StatechartDiagram.transitionOf` does.
     *
     * @property index the place of the transition among the transitions of the text, counted from 0, or -1 for a
     *   transition the engine made up.
     * @property kind the kind of the transition.
     * @property sources the names of the states it leaves.
     * @property targets the names of the states it enters.
     */
    public data class AtTransition(
        public val index: Int,
        public val kind: TransitionKind,
        public val sources: List<String>,
        public val targets: List<String>,
    ) : DiagramPause
}
