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
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.ui.diagram.DiagramLive
import io.karpfen.ide.ui.diagram.DiagramPause
import java.nio.file.Path

/**
 * One use of a file by a state machine instance, whose states a diagram of the file can show: the instance on the
 * object with [key], which runs the definition [definition], or a state machine the deployment attached to the object
 * if [definition] is null. [page] is null where the file is the state machine of the instance. Where the file is a
 * fragment, [page] is the flat name of the container state of the instance whose copy of the fragment is shown, such
 * as `self test` or `self test/wiring`, so one instance that uses a fragment twice is two uses. Immutable.
 */
internal data class LiveInstance(val key: String, val definition: String?, val page: String? = null) {
    /** The words of the use in the switch above the diagram: the key, the definition in parentheses and the page. */
    val label: String
        get() = (definition?.let { "$key ($it)" } ?: key) + (page?.let { ": $it" } ?: "")
}

/**
 * What a run adds to the diagram of a state machine file, and which use of the file it shows. Immutable.
 *
 * @property live the states and the pause the diagram marks, which are those of the use [shown], under the names the
 *   file gives them.
 * @property instances every use of the file by the run, the instances in the order the runtime announced them and the
 *   uses of one instance in the order of its states. It holds at least the use [shown].
 * @property shown the use the diagram shows.
 * @property fixed true while the debugger has paused the run at a breakpoint of the use [shown]. The diagram then
 *   shows that use whatever was chosen.
 */
internal data class DiagramRun(
    val live: DiagramLive,
    val instances: List<LiveInstance>,
    val shown: LiveInstance,
    val fixed: Boolean,
) {
    /**
     * True if the diagram offers a choice of the use it shows: when the run uses the file more than once, or when the
     * file is a fragment, whose diagram names the container state of the use it shows.
     */
    val offersChoice: Boolean
        get() = instances.size > 1 || instances.any { it.page != null }
}

/**
 * Tells what a run adds to the diagram of a file. The statechart of a state machine file gets the states of one use of
 * the file and, while the debugger has paused the run at a breakpoint of that use, the place of the breakpoint. Every
 * other file gets nothing.
 *
 * The runtime runs flat state machines, in which the states of a fragment that the container state `self test` uses
 * are named `self test/lit`. A use of a state machine file sees the states of its own text, a container state among
 * them while the run is inside the container. A use of a fragment file sees the states of one copy of the fragment
 * under the names the fragment gives them. The instance's [io.karpfen.ide.runtime.session.states.MachinePages] tell
 * which states and transitions belong to which use.
 *
 * Several uses of one file come from a definition that several objects behave after or got by `ATTACH` or
 * `EXCHANGE`, from a descriptor that attaches the file to several objects, and from a fragment that container states
 * use. The diagram shows one of them, chosen by this rule: the use the debugger halted in while the run is paused at a
 * breakpoint in the file, else the use the user chose while the run still has it, else the first use. So the choice
 * follows the uses: when the chosen one leaves, the diagram shows the first one left, and when none is left, the file
 * gets nothing.
 */
internal object DiagramLives {

    /**
     * What the run with the active [states] and the debugger in [debugger] adds to the diagram of [file], or null if
     * no instance of the run uses that file. [chosen] is the use the user chose for the file, or null if the user
     * chose none.
     */
    fun of(states: ActiveStates, file: Path, debugger: DebuggerState = DebuggerState.Unavailable, chosen: LiveInstance? = null): DiagramRun? {
        val target = file.toAbsolutePath().normalize()
        val uses = states.machines.values.flatMap { machine -> usesOf(machine, target) }
        if (uses.isEmpty()) return null
        val halt = ((debugger as? DebuggerState.Paused)?.place as? PausePlace.AtBreakpoint)?.halt
        val halted = halt?.let { found ->
            uses.firstNotNullOfOrNull { use ->
                if (use.machine.attachedTo != found.attachedTo) null else pauseOf(found, use)?.let { use to it }
            }
        }
        val shown = halted?.first ?: uses.firstOrNull { it.instance == chosen } ?: uses.first()
        return DiagramRun(
            live = DiagramLive(active = shown.machine.pages.localStates(shown.machine.stack, shown.instance.page).toSet(), pause = halted?.second),
            instances = uses.map { it.instance },
            shown = shown.instance,
            fixed = halted != null,
        )
    }

    /** The uses of the file [target] by [machine]: its own file, and each copy of a fragment written in [target]. */
    private fun usesOf(machine: MachineActivity, target: Path): List<Use> {
        val own = if (machine.file.toAbsolutePath().normalize() == target) listOf(null) else emptyList()
        return (own + machine.pages.pagesIn(target)).map { page -> Use(machine, LiveInstance(machine.attachedTo, machine.definition, page)) }
    }

    /**
     * The place of [halt] as the statechart of [use] marks it, or null if the halt lies in another use of the instance.
     * A transition is marked by its index in the text of the use and by its states there, so a transition the engine
     * made up, which has no index, is found by its states.
     */
    private fun pauseOf(halt: EngineHalt, use: Use): DiagramPause? {
        val pages = use.machine.pages
        val page = use.instance.page
        return when (halt) {
            is EngineHalt.AtState -> pages.localState(halt.state, page)?.let(DiagramPause::AtState)
            is EngineHalt.AtTransition -> {
                val index = pages.localTransition(halt.index, page) ?: return null
                val sources = pages.localStates(halt.from, page)
                val targets = pages.localStates(halt.to, page)
                if (sources.isEmpty() && targets.isEmpty()) null else DiagramPause.AtTransition(index, kindOf(halt.kind), sources, targets)
            }
        }
    }

    /** The kind of a transition in the diagram for the [kind] the engine names. */
    private fun kindOf(kind: HaltedTransitionKind): TransitionKind = when (kind) {
        HaltedTransitionKind.TRANSITION -> TransitionKind.NORMAL
        HaltedTransitionKind.SPLIT -> TransitionKind.SPLIT
        HaltedTransitionKind.JOIN -> TransitionKind.JOIN
    }

    /** The use [instance] of a file by the running [machine]. */
    private class Use(val machine: MachineActivity, val instance: LiveInstance)
}
