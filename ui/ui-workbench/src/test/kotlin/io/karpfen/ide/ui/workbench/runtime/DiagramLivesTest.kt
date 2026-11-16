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

import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.DslFlattener
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.Flattening
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.statechart.TransitionKind
import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.runtime.session.states.MachinePages
import io.karpfen.ide.runtime.session.states.StackOrigin
import io.karpfen.ide.ui.diagram.DiagramLive
import io.karpfen.ide.ui.diagram.DiagramPause
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests what a run adds to the diagram of a file: the active states of one instance for a state machine file, the
 * place where the debugger paused it, the choice among several instances of one definition, and nothing else. For a
 * state machine composed of pages, the host's diagram marks the container state the run is in, and the fragment's
 * diagram offers each use and shows the states and pauses of one use under the names of the fragment's text.
 */
class DiagramLivesTest {

    private val folder: Path = Path.of("/ws/lamp").toAbsolutePath()
    private val machine = folder.resolve("lamp.kstates")
    private val model = folder.resolve("lamp.kmodel")

    private val states = ActiveStates(
        machines = mapOf(
            "lamp" to MachineActivity("lamp", machine, listOf("on", "full"), listOf(listOf("on", "full")), null, StackOrigin.REPORTED),
            "wallSwitch" to MachineActivity("wallSwitch", folder.resolve("switch.kstates"), listOf("up"), listOf(listOf("up")), null, StackOrigin.REPORTED),
        ),
        unknownElements = emptyMap(),
    )

    @Test
    fun `the diagram of a state machine file gets the states its machine is in`() {
        assertEquals(DiagramLive(active = setOf("on", "full")), DiagramLives.of(states, machine)?.live)
        assertEquals(DiagramLive(active = setOf("up")), DiagramLives.of(states, folder.resolve("./switch.kstates"))?.live)
    }

    @Test
    fun `the diagram of the paused machine marks the state or the transition it is paused at`() {
        val atState = paused(EngineHalt.AtState("lamp", "full", StateTiming.EXIT))
        val atTransition = paused(EngineHalt.AtTransition("lamp", HaltedTransitionKind.SPLIT, listOf("off"), listOf("on", "full"), 3, TransitionTiming.END))

        assertEquals(DiagramLive(setOf("on", "full"), DiagramPause.AtState("full")), DiagramLives.of(states, machine, atState)?.live)
        assertEquals(
            DiagramLive(setOf("on", "full"), DiagramPause.AtTransition(3, TransitionKind.SPLIT, listOf("off"), listOf("on", "full"))),
            DiagramLives.of(states, machine, atTransition)?.live,
        )
    }

    @Test
    fun `a pause of another machine, between two ticks or of a running engine marks nothing`() {
        val plain = DiagramLive(active = setOf("on", "full"))

        assertEquals(plain, DiagramLives.of(states, machine, paused(EngineHalt.AtState("wallSwitch", "up", StateTiming.ENTRY)))?.live)
        assertEquals(plain, DiagramLives.of(states, machine, DebuggerState.Paused(PausePlace.BetweenTicks))?.live)
        assertEquals(plain, DiagramLives.of(states, machine, DebuggerState.Running)?.live)
        assertEquals(plain, DiagramLives.of(states, machine)?.live)
    }

    @Test
    fun `a file without a state machine of the run gets nothing`() {
        assertNull(DiagramLives.of(states, model))
        assertNull(DiagramLives.of(states, folder.resolve("lamp.kmeta")))
        assertNull(DiagramLives.of(states, Path.of("/elsewhere/lamp.kstates").toAbsolutePath()))
    }

    @Test
    fun `the diagram of a definition shows the first instance, names every instance and follows the choice`() {
        val first = DiagramLives.of(bulbs, glowing)

        assertEquals(listOf(LiveInstance("bulb", "glowing"), LiveInstance("gen_Bulb_0", "glowing")), first?.instances)
        assertEquals("bulb" to setOf("lit"), first?.shown?.key to first?.live?.active)
        assertEquals(false, first?.fixed)

        val chosen = DiagramLives.of(bulbs, glowing, chosen = LiveInstance("gen_Bulb_0", "glowing"))
        assertEquals("gen_Bulb_0" to setOf("dark"), chosen?.shown?.key to chosen?.live?.active)
    }

    @Test
    fun `a chosen instance that left falls back to the first one, and without instances the file gets nothing`() {
        val left = ActiveStates(bulbs.machines - "gen_Bulb_0", emptyMap())

        assertEquals("bulb", DiagramLives.of(left, glowing, chosen = LiveInstance("gen_Bulb_0", "glowing"))?.shown?.key)
        assertNull(DiagramLives.of(ActiveStates(emptyMap(), emptyMap()), glowing, chosen = LiveInstance("bulb", "glowing")))
    }

    @Test
    fun `while the debugger holds an instance at a breakpoint, the diagram shows that instance whatever was chosen`() {
        val run = DiagramLives.of(bulbs, glowing, paused(EngineHalt.AtState("gen_Bulb_0", "dark", StateTiming.ENTRY)), chosen = LiveInstance("bulb", "glowing"))

        assertEquals("gen_Bulb_0", run?.shown?.key)
        assertEquals(true, run?.fixed)
        assertEquals(DiagramLive(setOf("dark"), DiagramPause.AtState("dark")), run?.live)
    }

    @Test
    fun `the diagram of the paged lamp marks the container state while the run is inside it`() {
        val run = DiagramLives.of(paged(listOf("second test", "second test/lit")), pagedLamp)

        assertEquals(DiagramLive(active = setOf("second test")), run?.live)
        assertEquals(listOf(LiveInstance("lamp", null)), run?.instances)
        assertEquals(false, run?.offersChoice)
    }

    @Test
    fun `the diagram of a fragment offers each use and marks the states of the one shown by their local names`() {
        val states = paged(listOf("second test", "second test/lit"))

        val first = DiagramLives.of(states, bulbTest)
        assertEquals(listOf(LiveInstance("lamp", null, "first test"), LiveInstance("lamp", null, "second test")), first?.instances)
        assertEquals(LiveInstance("lamp", null, "first test") to emptySet<String>(), first?.shown to first?.live?.active)
        assertEquals(true, first?.offersChoice)
        assertEquals("lamp: first test", first?.shown?.label)

        val second = DiagramLives.of(states, bulbTest, chosen = LiveInstance("lamp", null, "second test"))
        assertEquals(DiagramLive(active = setOf("lit")), second?.live)
    }

    @Test
    fun `a pause inside a fragment shows at its box in the use it lies in and fixes that use`() {
        val states = paged(listOf("second test", "second test/lit"))
        val atState = paused(EngineHalt.AtState("lamp", "second test/lit", StateTiming.ENTRY))

        val fragment = DiagramLives.of(states, bulbTest, atState, chosen = LiveInstance("lamp", null, "first test"))
        assertEquals(LiveInstance("lamp", null, "second test"), fragment?.shown)
        assertEquals(true, fragment?.fixed)
        assertEquals(DiagramLive(setOf("lit"), DiagramPause.AtState("lit")), fragment?.live)

        val host = DiagramLives.of(states, pagedLamp, atState)
        assertEquals(DiagramLive(active = setOf("second test")), host?.live)
        assertEquals(false, host?.fixed)
    }

    @Test
    fun `a transition pause inside a fragment carries the index and the states of the fragment's text`() {
        val states = paged(listOf("second test", "second test/checked"))
        val halt = EngineHalt.AtTransition(
            "lamp", HaltedTransitionKind.TRANSITION, listOf("second test/lit"), listOf("second test/checked"), 4, TransitionTiming.END,
        )

        val fragment = DiagramLives.of(states, bulbTest, paused(halt))
        assertEquals(
            DiagramLive(setOf("checked"), DiagramPause.AtTransition(0, TransitionKind.NORMAL, listOf("lit"), listOf("checked"))),
            fragment?.live,
        )
        assertEquals(LiveInstance("lamp", null, "second test"), fragment?.shown)
        assertNull(DiagramLives.of(states, pagedLamp, paused(halt))?.live?.pause)
    }

    /** The paged lamp, whose container states first test and second test use the fragment bulb test. */
    private val pagedLamp = folder.resolve("paged_lamp.kstates")

    /** The fragment bulb test. */
    private val bulbTest = folder.resolve("bulb_test.kstates")

    /** The state machine of [text], read by the reader of dsl-tools. */
    private fun machineOf(text: String): StateMachine = checkNotNull(DslReader().kstates(TextSnapshot(text, 0)).model)

    /** The paged lamp running with [stack]. */
    private fun paged(stack: List<String>): ActiveStates {
        val fragment = machineOf(BULB_TEST)
        val flat = DslFlattener().flatten(machineOf(PAGED_LAMP), listOf(fragment)) as Flattening.Flat
        val pages = MachinePages.of(pagedLamp, flat, mapOf("bulb test" to bulbTest))
        return ActiveStates(
            machines = mapOf("lamp" to MachineActivity("lamp", pagedLamp, stack, listOf(stack), null, StackOrigin.REPORTED, null, pages)),
            unknownElements = emptyMap(),
        )
    }

    /** The definition glowing. */
    private val glowing = folder.resolve("glowing.kstates")

    /** Two bulbs running glowing, announced in that order. */
    private val bulbs = ActiveStates(
        machines = linkedMapOf(
            "bulb" to MachineActivity("bulb", glowing, listOf("lit"), listOf(listOf("lit")), null, StackOrigin.REPORTED, "glowing"),
            "gen_Bulb_0" to MachineActivity("gen_Bulb_0", glowing, listOf("dark"), listOf(listOf("dark")), null, StackOrigin.PREDICTED, "glowing"),
        ),
        unknownElements = emptyMap(),
    )

    /** The debugger's state while the engine waits at [halt]. */
    private fun paused(halt: EngineHalt): DebuggerState = DebuggerState.Paused(PausePlace.AtBreakpoint(halt))

    private companion object {
        /** The fragment bulb test: from lit through checked into its final state. */
        val BULB_TEST = """
            STATEMACHINE "bulb test" FRAGMENT {
                STATES {
                    INITIAL STATE "lit" { }
                    STATE "checked" { }
                    FINAL STATE "passed" { }
                }
                TRANSITIONS {
                    TRANSITION "lit" -> "checked" { }
                    TRANSITION "checked" -> "passed" { }
                }
            }
        """.trimIndent()

        /** A lamp that runs bulb test in first test and then in second test, with two transitions of its own. */
        val PAGED_LAMP = """
            STATEMACHINE ATTACHED TO "Lamp" {
                STATES {
                    INITIAL STATE "off" { }
                    CONTAINER STATE "first test" USE "bulb test" { }
                    CONTAINER STATE "second test" USE "bulb test" { }
                }
                TRANSITIONS {
                    TRANSITION "off" -> "first test" { }
                    TRANSITION "first test" -> "second test" {
                        CONDITION { DONE }
                    }
                }
            }
        """.trimIndent()
    }
}
