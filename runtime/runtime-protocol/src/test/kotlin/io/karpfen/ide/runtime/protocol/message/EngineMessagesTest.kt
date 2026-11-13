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
package io.karpfen.ide.runtime.protocol.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [EngineMessages]: the messages a debugger sends to the features `TickByTick` and `Breakpoint`, the reading of
 * the answer to a step, and the reading of the status with its halt at a state or a transition. The statuses are
 * written as the runtime's org.json writes them.
 */
class EngineMessagesTest {

    @Test
    fun `the messages are those the features take`() {
        assertEquals("pause", EngineMessages.PAUSE)
        assertEquals("resume", EngineMessages.RESUME)
        assertEquals("tick:1", EngineMessages.ticks(1))
        assertEquals("tick:5", EngineMessages.ticks(5))
        assertEquals("""{"action": "getStatus"}""", EngineMessages.STATUS)
        assertFailsWith<IllegalArgumentException> { EngineMessages.ticks(0) }
    }

    @Test
    fun `only the answer to an applied step says the engine advances`() {
        assertTrue(EngineMessages.advances("execution is advancing 1 tick"))
        assertTrue(EngineMessages.advances("execution is advancing 3 ticks"))
        assertFalse(EngineMessages.advances("execution is currently not paused, ticks will not be applied"))
        assertFalse(EngineMessages.advances(""))
    }

    @Test
    fun `a status without a halt is read as paused and waiting or not`() {
        assertEquals(EngineStatus(paused = false, waiting = false, halt = null), EngineMessages.status("""{"paused":false,"waiting":false}"""))
        assertEquals(EngineStatus(paused = true, waiting = true, halt = null), EngineMessages.status("""{"waiting":true,"paused":true}"""))
    }

    @Test
    fun `a halt at a state is read with the state and its timing`() {
        val status = EngineMessages.status(
            """{"paused":true,"waiting":true,"halt":{"modelElementId":"crossing","timing":"entry","state":"cars wait"}}""",
        )

        assertEquals(EngineStatus(true, true, EngineHalt.AtState("crossing", "cars wait", StateTiming.ENTRY)), status)
        assertEquals(
            EngineHalt.AtState("crossing", "operating", StateTiming.EXIT),
            EngineMessages.status("""{"paused":true,"waiting":true,"halt":{"modelElementId":"crossing","timing":"exit","state":"operating"}}""")?.halt,
        )
    }

    @Test
    fun `a halt at a transition is read with its kind, its states, its index and its timing`() {
        val status = EngineMessages.status(
            """{"paused":true,"waiting":true,"halt":{"modelElementId":"crossing","timing":"start",""" +
                """"transition":"Transition(fromState=maintenance, toState=operating)","kind":"TRANSITION",""" +
                """"from":["maintenance"],"to":["operating"],"index":2}}""",
        )
        val split = EngineMessages.status(
            """{"paused":true,"waiting":false,"halt":{"modelElementId":"r","timing":"end","transition":"SplitTransition(x)",""" +
                """"kind":"SPLIT","from":["a"],"to":["b","c"],"index":-1}}""",
        )

        assertEquals(
            EngineHalt.AtTransition("crossing", HaltedTransitionKind.TRANSITION, listOf("maintenance"), listOf("operating"), 2, TransitionTiming.START),
            status?.halt,
        )
        assertEquals(EngineHalt.AtTransition("r", HaltedTransitionKind.SPLIT, listOf("a"), listOf("b", "c"), -1, TransitionTiming.END), split?.halt)
    }

    @Test
    fun `an answer that is not a status as the runtime writes it is not read`() {
        listOf(
            "",
            "Error when parsing the message: broken",
            "[]",
            """{"paused":"yes","waiting":true}""",
            """{"paused":true}""",
            """{"paused":true,"waiting":true,"halt":"cars wait"}""",
            """{"paused":true,"waiting":true,"halt":{"modelElementId":"c","timing":"start","state":"s"}}""",
            """{"paused":true,"waiting":true,"halt":{"modelElementId":"c","timing":"start","kind":"LOOP","from":[],"to":[],"index":0}}""",
            """{"paused":true,"waiting":true,"halt":{"modelElementId":"c","timing":"end","kind":"JOIN","from":["a"],"to":["b"],"index":1.5}}""",
            """{"paused":true,"waiting":true,"halt":{"timing":"entry","state":"s"}}""",
        ).forEach { assertNull(EngineMessages.status(it), it) }
    }
}
