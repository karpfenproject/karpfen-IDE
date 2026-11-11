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

import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers forcing the states of the lamp's machines through a session against a real runtime. A forced state runs no
 * entry block, and the machine continues from it with the next event. The second machine can be forced on its own. An
 * unknown state comes back as the session's refusal with the runtime's text.
 */
abstract class ForcedStateScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `a forced state runs no entry block, and the machine continues from it with the next event`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)
        session.awaitModel("both machines in their initial states") { it.stacks == INITIAL && it.mirror.objectOf("lamp").texts("log") == listOf("off") }

        assertEquals(SessionCommandOutcome.Done, session.forceState("lamp", "full power"))
        session.awaitStack("lamp", listOf("on", "full power"))

        val forced = session.pulled()
        assertEquals("off", forced.mirror.objectOf("lamp").text("mode"))
        assertEquals(40.0, forced.mirror.objectOf("bulb").number("watts"))
        session.inject("switch", "press")
        session.awaitStack("lamp", listOf("off"))
        session.awaitModel("one more off in the log") { it.mirror.objectOf("lamp").texts("log") == listOf("off", "off") }
    }

    @Test
    fun `the second machine is forced on its own`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)
        val before = session.awaitModel("both machines in their initial states") { it.stacks == INITIAL }.mirror.objectOf("wallSwitch").texts("presses")

        assertEquals(SessionCommandOutcome.Done, session.forceState("wallSwitch", "up"))
        session.awaitStack("wallSwitch", listOf("up"))

        assertEquals(before, session.pulled().mirror.objectOf("wallSwitch").texts("presses"))
        assertEquals(listOf("off"), session.model.value.stacks["lamp"])
    }

    @Test
    fun `an unknown state is refused with the runtime's text`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP)

        val refused = session.forceState("lamp", "nowhere")

        assertEquals(SessionCommandOutcome.Refused(session.state.value, "Unknown state 'nowhere' for model element 'lamp'"), refused)
    }

    private companion object {
        /** The initial stacks of the lamp's machines. */
        val INITIAL = mapOf("lamp" to listOf("off"), "wallSwitch" to listOf("down"))
    }
}
