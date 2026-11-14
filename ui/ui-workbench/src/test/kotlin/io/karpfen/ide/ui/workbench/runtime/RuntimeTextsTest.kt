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

import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.runtime.session.states.StackOrigin
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the words of the Runtime tool window for state machine instances and for the place of a pause. */
class RuntimeTextsTest {

    @Test
    fun `the active states name the object and, for an instance of a definition, the definition`() {
        val attached = MachineActivity("lamp", Path.of("lamp.kstates"), listOf("on", "full"), listOf(listOf("on", "full")), null, StackOrigin.REPORTED)
        val instance = attached.copy(attachedTo = "gen_Bulb_0", file = Path.of("glowing.kstates"), stack = listOf("lit"), definition = "glowing")

        assertEquals("lamp: on > full", RuntimeTexts.activeStates(attached))
        assertEquals("gen_Bulb_0 (glowing): lit", RuntimeTexts.activeStates(instance))
    }

    @Test
    fun `a pause at a breakpoint names the object whose instance the engine halted`() {
        assertEquals(
            "Paused before the entry of lit in gen_Bulb_0",
            RuntimeTexts.pause(PausePlace.AtBreakpoint(EngineHalt.AtState("gen_Bulb_0", "lit", StateTiming.ENTRY))),
        )
        assertEquals(
            "Paused after the transition dark to lit in bulb",
            RuntimeTexts.pause(
                PausePlace.AtBreakpoint(EngineHalt.AtTransition("bulb", HaltedTransitionKind.TRANSITION, listOf("dark"), listOf("lit"), 0, TransitionTiming.END)),
            ),
        )
        assertEquals("Paused between two ticks", RuntimeTexts.pause(PausePlace.BetweenTicks))
    }
}
