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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.ui.diagram.DiagramLive
import io.karpfen.ide.ui.diagram.DiagramPause
import java.nio.file.Path

/**
 * One state machine instance whose states a diagram can show: the instance on the object with [key], which runs the
 * definition [definition], or a state machine the deployment attached to the object if [definition] is null.
 * Immutable.
 */
internal data class LiveInstance(val key: String, val definition: String?)

/**
 * What a run adds to the diagram of a state machine file, and which instance it shows. Immutable.
 *
 * @property live the states and the pause the diagram marks, which are those of the instance [shown].
 * @property instances every instance that runs the file, in the order the runtime announced them. It holds at least
 *   the instance [shown].
 * @property shown the key of the object whose instance the diagram shows.
 * @property fixed true while the debugger has paused the run at a breakpoint of the instance [shown]. The diagram then
 *   shows that instance whatever was chosen.
 */
internal data class DiagramRun(
    val live: DiagramLive,
    val instances: List<LiveInstance>,
    val shown: String,
    val fixed: Boolean,
)

/**
 * Tells what a run adds to the diagram of a file. The statechart of a state machine file gets the states of one
 * instance that runs the file and, while the debugger has paused the run at a breakpoint of that instance, the place of
 * the breakpoint. Every other file gets nothing.
 *
 * Several instances run one file when the file is a definition that several objects behave after or got by `ATTACH`
 * or `EXCHANGE`, or when the descriptor attaches the file to several objects. The diagram shows one of them, chosen
 * by this rule: the instance the debugger halted at while the run is paused at a breakpoint in the file, else the
 * instance the user chose while it still runs the file, else the first instance the runtime announced. So the choice
 * follows the instances: when the chosen one leaves, the diagram shows the first one left, and when none is left, the
 * file gets nothing.
 */
internal object DiagramLives {

    /**
     * What the run with the active [states] and the debugger in [debugger] adds to the diagram of [file], or null if
     * no instance of the run runs that file. [chosen] is the key of the object whose instance the user chose for the
     * file, or null if the user chose none.
     */
    fun of(states: ActiveStates, file: Path, debugger: DebuggerState = DebuggerState.Unavailable, chosen: String? = null): DiagramRun? {
        val target = file.toAbsolutePath().normalize()
        val machines = states.machines.values.filter { it.file.toAbsolutePath().normalize() == target }
        if (machines.isEmpty()) return null
        val halt = ((debugger as? DebuggerState.Paused)?.place as? PausePlace.AtBreakpoint)?.halt
        val halted = halt?.let { found -> machines.firstOrNull { it.attachedTo == found.attachedTo } }
        val shown = halted ?: machines.firstOrNull { it.attachedTo == chosen } ?: machines.first()
        return DiagramRun(
            live = DiagramLive(active = shown.stack.toSet(), pause = halt?.takeIf { halted != null }?.let(::pauseOf)),
            instances = machines.map { LiveInstance(it.attachedTo, it.definition) },
            shown = shown.attachedTo,
            fixed = halted != null,
        )
    }

    /** The place of [halt] as a statechart marks it. */
    private fun pauseOf(halt: EngineHalt): DiagramPause = when (halt) {
        is EngineHalt.AtState -> DiagramPause.AtState(halt.state)
        is EngineHalt.AtTransition -> DiagramPause.AtTransition(halt.index, kindOf(halt.kind), halt.from, halt.to)
    }

    /** The kind of a transition in the diagram for the [kind] the engine names. */
    private fun kindOf(kind: HaltedTransitionKind): TransitionKind = when (kind) {
        HaltedTransitionKind.TRANSITION -> TransitionKind.NORMAL
        HaltedTransitionKind.SPLIT -> TransitionKind.SPLIT
        HaltedTransitionKind.JOIN -> TransitionKind.JOIN
    }
}
