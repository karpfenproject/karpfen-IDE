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

import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.runtime.session.states.StackOrigin
import io.karpfen.ide.ui.diagram.DiagramLive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests what a run adds to the diagram of a file: the active states for a state machine file, and nothing else. */
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
        assertEquals(DiagramLive(active = setOf("on", "full")), DiagramLives.of(states, machine))
        assertEquals(DiagramLive(active = setOf("up")), DiagramLives.of(states, folder.resolve("./switch.kstates")))
    }

    @Test
    fun `a file without a state machine of the run gets nothing`() {
        assertNull(DiagramLives.of(states, model))
        assertNull(DiagramLives.of(states, folder.resolve("lamp.kmeta")))
        assertNull(DiagramLives.of(states, Path.of("/elsewhere/lamp.kstates").toAbsolutePath()))
    }
}
