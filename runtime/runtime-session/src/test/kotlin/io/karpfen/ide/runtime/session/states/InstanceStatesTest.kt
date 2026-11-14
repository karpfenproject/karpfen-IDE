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
package io.karpfen.ide.runtime.session.states

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the instances of definitions in the active states: announced instances with the name of their definition,
 * instances that leave with their stack, an exchange on one object, the record of instances in a snapshot, and the
 * keys the deployment cannot map to a state machine.
 */
class InstanceStatesTest {

    private val reducer = ActiveStatesReducer(listOf(ROOM), listOf(GLOWING, FLICKERING))

    @Test
    fun `before the start only the attached machine shows, with its prediction`() {
        val states = reducer.initial()

        assertEquals(listOf("room"), states.machines.keys.toList())
        assertNull(states.machines.getValue("room").definition)
    }

    @Test
    fun `an announced instance shows the predicted initial stack of its definition until it reports`() {
        val announced = reducer.attached(reducer.initial(), "gen_Bulb_0", "glowing")
        val instance = announced.machines.getValue("gen_Bulb_0")

        assertEquals("glowing", instance.definition)
        assertEquals(GLOWING.file, instance.file)
        assertEquals(listOf("dark") to StackOrigin.PREDICTED, instance.stack to instance.origin)

        val reported = reducer.reported(announced, "gen_Bulb_0", listOf("lit")).machines.getValue("gen_Bulb_0")
        assertEquals(listOf("lit") to StackOrigin.REPORTED, reported.stack to reported.origin)
        assertEquals("glowing", reported.definition)
    }

    @Test
    fun `a detached instance is gone with its stack`() {
        val running = reducer.reported(reducer.attached(reducer.initial(), "bulb", "glowing"), "bulb", listOf("lit"))

        val left = reducer.detached(running, "bulb")

        assertEquals(listOf("room"), left.machines.keys.toList())
        assertEquals(emptyMap(), left.unknownElements)
    }

    @Test
    fun `an exchange detaches the old instance and attaches the new one on the same key, which starts in its initial state`() {
        val glowing = reducer.reported(reducer.attached(reducer.initial(), "bulb", "glowing"), "bulb", listOf("lit"))

        val exchanged = reducer.attached(reducer.detached(glowing, "bulb"), "bulb", "flickering").machines.getValue("bulb")

        assertEquals("flickering", exchanged.definition)
        assertEquals(FLICKERING.file, exchanged.file)
        assertEquals(listOf("on") to StackOrigin.PREDICTED, exchanged.stack to exchanged.origin)
        assertNull(exchanged.previous)
    }

    @Test
    fun `an instance of a definition the deployment did not upload is an unknown element`() {
        val states = reducer.attached(reducer.initial(), "bulb", "elsewhere")

        assertEquals(listOf("room"), states.machines.keys.toList())
        assertEquals(mapOf("bulb" to emptyList<String>()), states.unknownElements)
    }

    @Test
    fun `a snapshot of a started environment makes the machines exactly its record of instances`() {
        val before = reducer.attached(reducer.initial(), "gone", "glowing")

        val states = reducer.replaced(
            before,
            reported = mapOf("room" to listOf("stocked"), "bulb" to listOf("lit")),
            instances = linkedMapOf("room" to null, "bulb" to "glowing", "gen_Bulb_0" to "flickering"),
            started = true,
        )

        assertEquals(listOf("room", "bulb", "gen_Bulb_0"), states.machines.keys.toList())
        assertEquals(listOf(null, "glowing", "flickering"), states.machines.values.map { it.definition })
        assertEquals(
            listOf(StackOrigin.REPORTED, StackOrigin.REPORTED, StackOrigin.PREDICTED),
            states.machines.values.map { it.origin },
        )
    }

    @Test
    fun `a snapshot before the start keeps the attached machines whatever it lists`() {
        val states = reducer.replaced(reducer.initial(), reported = emptyMap(), instances = emptyMap(), started = false)

        assertEquals(listOf("room"), states.machines.keys.toList())
        assertEquals(StackOrigin.PREDICTED, states.machines.getValue("room").origin)
    }

    private companion object {
        /** The room's attached machine. */
        val ROOM = MachineLayout("room", Path.of("room.kstates"), listOf("stocked", "empty"), listOf("stocked"))

        /** The definition glowing. */
        val GLOWING = DefinitionLayout("glowing", Path.of("glowing.kstates"), listOf("dark", "lit"), listOf("dark"))

        /** The definition flickering. */
        val FLICKERING = DefinitionLayout("flickering", Path.of("flickering.kstates"), listOf("on", "off"), listOf("on"))
    }
}
