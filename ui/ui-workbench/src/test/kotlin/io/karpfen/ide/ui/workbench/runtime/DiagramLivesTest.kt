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
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.runtime.session.states.StackOrigin
import io.karpfen.ide.ui.diagram.DiagramLive
import io.karpfen.ide.ui.diagram.DiagramPause
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests what a run adds to the diagram of a file: the active states of one instance for a state machine file, the
 * place where the debugger paused it, the choice among several instances of one definition, and nothing else.
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
        assertEquals("bulb" to setOf("lit"), first?.shown to first?.live?.active)
        assertEquals(false, first?.fixed)

        val chosen = DiagramLives.of(bulbs, glowing, chosen = "gen_Bulb_0")
        assertEquals("gen_Bulb_0" to setOf("dark"), chosen?.shown to chosen?.live?.active)
    }

    @Test
    fun `a chosen instance that left falls back to the first one, and without instances the file gets nothing`() {
        val left = ActiveStates(bulbs.machines - "gen_Bulb_0", emptyMap())

        assertEquals("bulb", DiagramLives.of(left, glowing, chosen = "gen_Bulb_0")?.shown)
        assertNull(DiagramLives.of(ActiveStates(emptyMap(), emptyMap()), glowing, chosen = "bulb"))
    }

    @Test
    fun `while the debugger holds an instance at a breakpoint, the diagram shows that instance whatever was chosen`() {
        val run = DiagramLives.of(bulbs, glowing, paused(EngineHalt.AtState("gen_Bulb_0", "dark", StateTiming.ENTRY)), chosen = "bulb")

        assertEquals("gen_Bulb_0", run?.shown)
        assertEquals(true, run?.fixed)
        assertEquals(DiagramLive(setOf("dark"), DiagramPause.AtState("dark")), run?.live)
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
}
