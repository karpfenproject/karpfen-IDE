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
package io.karpfen.ide.runtime.session

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.awaitValue
import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.PausePlace
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the debugger of a session against a real runtime. The crossing gets a breakpoint at the entry of its state
 * `cars wait` and one at the start of its transition from `maintenance` to `operating`. A project that has
 * breakpoints, histories and tick limits switched on deploys it, so the deployment switches the runtime's feature
 * `Breakpoint` on and the session has a debugger.
 *
 * A pedestrian halts the engine before the entry of `cars wait`. A step runs the halted tick to its end, where the
 * engine waits between two ticks, and a resume lets the machine reach `cars wait`. A service and then a resume event
 * halt the engine before the transition back to `operating`, which is the third transition of the file. A resume runs
 * the transition, and the deep history leads back to `cars wait`, whose entry halts the engine once more. A last
 * resume lets it run on.
 */
abstract class DebuggerScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `the debugger halts at breakpoints of a state and a transition, steps to the end of the tick and resumes`() = runTest {
        val descriptor = copy(SynthesizedModel.CROSSING)
        addBreakpoints(descriptor.resolveSibling("crossing.kstates"))
        val session = deploy(prepare(open(descriptor, FEATURES), descriptor))
        assertTrue(session.debugger.isPresent, diagnostics)
        awaitDebugger(session, DebuggerState.Running)

        session.inject("street", "pedestrian")
        awaitDebugger(session, paused(EngineHalt.AtState("crossing", "cars wait", StateTiming.ENTRY)))
        assertEquals(SessionCommandOutcome.Done, session.debugger.step(), diagnostics)
        awaitDebugger(session, DebuggerState.Paused(PausePlace.BetweenTicks))
        assertEquals(SessionCommandOutcome.Done, session.debugger.resume(), diagnostics)
        session.awaitStack("crossing", WAIT)
        awaitDebugger(session, DebuggerState.Running)

        session.inject("street", "service")
        session.awaitStack("crossing", listOf("maintenance"))
        session.inject("street", "resume")
        val start = EngineHalt.AtTransition(
            "crossing", HaltedTransitionKind.TRANSITION, listOf("maintenance"), listOf("operating"), BACK_TO_OPERATING, TransitionTiming.START,
        )
        awaitDebugger(session, paused(start))
        assertEquals(SessionCommandOutcome.Done, session.debugger.resume(), diagnostics)

        awaitDebugger(session, paused(EngineHalt.AtState("crossing", "cars wait", StateTiming.ENTRY)))
        assertEquals(SessionCommandOutcome.Done, session.debugger.resume(), diagnostics)
        awaitDebugger(session, DebuggerState.Running)
        session.awaitStack("crossing", WAIT)
    }

    /** Runs [scenario] against the runtime with its models below [root]. */
    private fun runTest(scenario: suspend SessionScenario.() -> Unit) = runtime.run(root, scenario)

    /** Waits until the debugger of [session] shows [expected]. */
    private suspend fun SessionScenario.awaitDebugger(session: RuntimeSession, expected: DebuggerState) {
        awaitValue("the debugger $expected in ${session.environmentOrNull}", session.debugger.state) { it == expected }
    }

    /**
     * Puts `BREAKPOINT { ENTRY }` first into the body of the state `cars wait` and `BREAKPOINT { START }` into the
     * transition from `maintenance` to `operating` of the crossing's state machine [machine].
     */
    private fun addBreakpoints(machine: Path) {
        val text = Files.readString(machine)
        check(CARS_WAIT in text && BACK in text) { "the crossing's state machine changed: $text" }
        val edited = text.replace(CARS_WAIT, "$CARS_WAIT\n                BREAKPOINT { ENTRY }").replace(BACK, "$BACK\n            BREAKPOINT { START }")
        Files.writeString(machine, edited)
    }

    private companion object {
        /** The language features of the project: breakpoints, and the histories and tick limits the crossing uses. */
        val FEATURES: Set<KstatesFeature> = setOf(KstatesFeature.BREAKPOINTS, KstatesFeature.HISTORY, KstatesFeature.TICK_LIMIT)

        /** The stack of the crossing while the cars wait. */
        val WAIT = listOf("operating", "cars wait")

        /** The head of the state `cars wait` in the crossing's state machine. */
        const val CARS_WAIT = "STATE \"cars wait\" {"

        /** The head of the transition from `maintenance` to `operating` in the crossing's state machine. */
        const val BACK = "TRANSITION \"maintenance\" -> \"operating\" {"

        /** The place of that transition among the transitions of the file, counted from 0. */
        const val BACK_TO_OPERATING = 2

        /** The debugger's state while the engine waits at [halt]. */
        fun paused(halt: EngineHalt): DebuggerState = DebuggerState.Paused(PausePlace.AtBreakpoint(halt))
    }
}
