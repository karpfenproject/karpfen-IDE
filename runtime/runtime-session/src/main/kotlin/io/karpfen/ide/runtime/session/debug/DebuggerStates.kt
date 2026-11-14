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
package io.karpfen.ide.runtime.session.debug

import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.EngineStatus
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming

/**
 * How the status of a running engine becomes the state of the debugger, and how a pause is told in the journal.
 * Stateless.
 *
 * An engine with a halt is paused at that breakpoint, also in the short moment before its thread starts to wait there.
 * An engine without a halt that is paused and waits is paused between two ticks. Any other engine runs, also one that
 * was asked to pause or was stepped and still finishes its tick.
 */
internal object DebuggerStates {

    /** The state of the debugger of an engine with [status]. */
    fun of(status: EngineStatus): DebuggerState {
        val halt = status.halt
        return when {
            halt != null -> DebuggerState.Paused(PausePlace.AtBreakpoint(halt))
            status.paused && status.waiting -> DebuggerState.Paused(PausePlace.BetweenTicks)
            else -> DebuggerState.Running
        }
    }

    /**
     * The journal's note of a pause at [place], such as `Paused before the entry of the state cars wait of crossing.`
     * The state machine is named by the key of the object its instance runs on, which tells the instances of one
     * definition apart.
     */
    fun note(place: PausePlace): String = when (place) {
        PausePlace.BetweenTicks -> "Paused between two ticks."
        is PausePlace.AtBreakpoint -> when (val halt = place.halt) {
            is EngineHalt.AtState -> {
                val side = if (halt.timing == StateTiming.ENTRY) "entry" else "exit"
                "Paused before the $side of the state ${halt.state} of ${halt.attachedTo}."
            }
            is EngineHalt.AtTransition -> {
                val side = if (halt.timing == TransitionTiming.START) "before" else "after"
                "Paused $side the transition ${halt.from.joinToString(" and ")} to ${halt.to.joinToString(" and ")} of ${halt.attachedTo}."
            }
        }
    }

    /** The journal's note of an engine that runs freely again. */
    const val RESUMED: String = "Resumed."
}
