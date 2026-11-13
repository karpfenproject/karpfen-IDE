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
 * Tells what a run adds to the diagram of a file. The statechart of a state machine file gets the states its machine
 * is in and, while the debugger has paused the run at a breakpoint of that machine, the place of the breakpoint. Every
 * other file gets nothing.
 */
internal object DiagramLives {

    /**
     * What the run with the active [states] and the debugger in [debugger] adds to the diagram of [file], or null if
     * no state machine of the run comes from that file. A state machine that is attached to several objects shows the
     * states of all of them. The place of a pause is shown in the file of the machine whose object the engine halted
     * at.
     */
    fun of(states: ActiveStates, file: Path, debugger: DebuggerState = DebuggerState.Unavailable): DiagramLive? {
        val target = file.toAbsolutePath().normalize()
        val machines = states.machines.values.filter { it.file.toAbsolutePath().normalize() == target }
        if (machines.isEmpty()) return null
        val halt = ((debugger as? DebuggerState.Paused)?.place as? PausePlace.AtBreakpoint)?.halt
        val pause = halt?.takeIf { found -> machines.any { it.attachedTo == found.attachedTo } }?.let(::pauseOf)
        return DiagramLive(active = machines.flatMap { it.stack }.toSet(), pause = pause)
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
