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

import io.karpfen.ide.runtime.session.mirror.MirrorObject
import io.karpfen.ide.runtime.session.mirror.MirrorProperty
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.ObjectOrigin
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.runtime.session.states.StackOrigin
import io.karpfen.ide.ui.diagram.DiagramLive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests what a run adds to the diagram of a file: active states for a state machine, current values for the model. */
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

    private val mirror = MirrorSnapshot(
        objects = mapOf(
            "bulb" to MirrorObject(
                id = "bulb",
                typeName = "Bulb",
                properties = listOf(
                    MirrorProperty("watts", PropertyValue.Single(ScalarValue.Number(30.0))),
                    MirrorProperty("lit", PropertyValue.Single(ScalarValue.Flag(true))),
                    MirrorProperty("ratio", PropertyValue.Single(ScalarValue.Number(0.75))),
                    MirrorProperty("presses", PropertyValue.Many(listOf(ScalarValue.Text("up"), ScalarValue.Text("down")))),
                ),
                relations = emptyList(),
                origin = ObjectOrigin.entries.first(),
                revision = 3,
            ),
        ),
        roots = emptyList(),
        containment = emptyMap(),
    )

    @Test
    fun `the diagram of a state machine file gets the states its machine is in`() {
        assertEquals(DiagramLive(active = setOf("on", "full")), DiagramLives.of(states, mirror, model, machine))
        assertEquals(DiagramLive(active = setOf("up")), DiagramLives.of(states, mirror, model, folder.resolve("./switch.kstates")))
    }

    @Test
    fun `the diagram of the model file gets the current values, written as a row shows them`() {
        val live = DiagramLives.of(states, mirror, model, model)

        assertEquals(
            mapOf("bulb" to listOf("watts" to "30", "lit" to "true", "ratio" to "0.75", "presses" to "[up, down]")),
            live?.values,
        )
        assertEquals(emptySet(), live?.active)
    }

    @Test
    fun `a file the run has nothing to do with gets nothing`() {
        assertNull(DiagramLives.of(states, mirror, model, folder.resolve("lamp.kmeta")))
        assertNull(DiagramLives.of(states, mirror, model, Path.of("/elsewhere/lamp.kmodel").toAbsolutePath()))
    }
}
