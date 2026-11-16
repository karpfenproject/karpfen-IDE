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

import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests reading the engine trace out of the runtime's console output and the simplified log built from it. The lines
 * are written the way the runtime's trace logger formats them.
 */
class EngineTraceTest {

    @Test
    fun `a trace line is taken apart into time, environment, machine, kind, message and details`() {
        assertEquals(
            EngineTraceLine("12:41:30.123", "env-1", "lamp", "TRANSITION_FIRED", "off -> on", mapOf("from" to "off", "to" to "on")),
            EngineTrace.parse("[12:41:30.123] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on"),
        )
        assertEquals(
            EngineTraceLine("12:41:29.001", "env-1", "*", "ENGINE_START", "Engine started with 2 state machine(s), tickDelay=500ms"),
            EngineTrace.parse("[12:41:29.001] [env-1] [*] ENGINE_START: Engine started with 2 state machine(s), tickDelay=500ms"),
        )
    }

    @Test
    fun `other output of the runtime is no trace line`() {
        assertNull(EngineTrace.parse("[WebSocket] Received from ide-1: hello"))
        assertNull(EngineTrace.parse("[Main] Server is running. Press Ctrl+C to stop."))
        assertNull(EngineTrace.parse(""))
    }

    @Test
    fun `the lines of every tick and the announcements of transitions are noise, the steps of a state machine are not`() {
        listOf(
            "[12:41:30.000] [env-1] [lamp] TICK_START: Tick #7 | state=off",
            "[12:41:30.122] [env-1] [lamp] TRANSITION_START: off -> on | from=off, to=on",
            "[12:41:30.122] [env-1] [lamp] SPLIT_START: off -> a,b | from=off, to=a,b",
            "[12:41:30.122] [env-1] [lamp] JOIN_START: a,b -> on | from=a,b, to=on",
            "[12:41:30.001] [env-1] [lamp] STATE_DO_SKIP: onDo for state 'off' is empty \u2014 skipped | state=off",
            "[12:41:30.002] [env-1] [lamp] TICK_END: Tick #7 completed | state=off",
        ).forEach { assertTrue(EngineTrace.isNoise(it), it) }
        listOf(
            "[12:41:30.123] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on",
            "[12:41:30.124] [env-1] [lamp] ACTION_ERROR: Error in onEntry for state 'on': boom | state=on",
            "[WebSocket] Connection closed",
        ).forEach { assertFalse(EngineTrace.isNoise(it), it) }
    }

    @Test
    fun `a step is put into a few words`() {
        assertEquals("starts in on > full power", describe("INITIAL_STATE: Initial state stack: on > full power | stack=on,full power"))
        assertEquals("set to broken", describe("INITIAL_STATE: Forced state: broken | stack=broken"))
        assertEquals("off -> on", describe("TRANSITION_FIRED: off -> on | from=off, to=on"))
        assertEquals("split idle -> left, right", describe("SPLIT_FIRED: idle -> left,right | from=idle, to=left,right"))
        assertEquals("join left, right -> done", describe("JOIN_FIRED: left,right -> done | from=left,right, to=done"))
        assertEquals("runs the entry actions of on", describe("STATE_ENTRY_EXEC: Executing onEntry for state 'on' | state=on"))
        assertEquals("Engine stopped after 12 ticks", describe("ENGINE_STOP: Engine stopped after 12 ticks"))
    }

    @Test
    fun `the simplified log shows the steps and the errors and nothing else`() {
        val step = line(1, OutputLevel.INFO, "[12:41:30.123] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on")
        val engine = line(2, OutputLevel.INFO, "[12:41:29.001] [env-1] [*] ENGINE_START: Engine started with 2 state machine(s)")
        val failed = line(3, OutputLevel.INFO, "[12:41:31.000] [env-1] [wallSwitch] ACTION_ERROR: Error in onEntry for state 'up': boom")
        val stderr = line(4, OutputLevel.ERROR, "[BranchRunner] Error executing onEntry for state 'up': boom")
        val chatter = line(5, OutputLevel.INFO, "[WebSocket] Received from ide-1: hello")

        assertEquals(listOf(step, engine, failed, stderr), listOf(step, engine, failed, stderr, chatter).filter(StatechartLog::shows))
        assertEquals(StatechartLogEntry("12:41:30.123", "lamp", "off -> on", isError = false), StatechartLog.entry(step))
        assertEquals("engine", StatechartLog.entry(engine).subject)
        assertTrue(StatechartLog.entry(failed).isError)
        assertEquals("" to true, StatechartLog.entry(stderr).let { it.subject to it.isError })
        assertEquals("[BranchRunner] Error executing onEntry for state 'up': boom", StatechartLog.entry(stderr).text)
        assertEquals("wallSwitch".length, StatechartLog.subjectWidth(listOf(step, engine, failed, stderr)))
    }

    @Test
    fun `an object that gets or loses a state machine says so, with the definition or its own state machine`() {
        assertEquals("attaches glowing", describe("STATE_MACHINE_ATTACHED: State machine 'glowing' attached | stateMachine=glowing"))
        assertEquals("detaches glowing", describe("STATE_MACHINE_DETACHED: State machine 'glowing' detached | stateMachine=glowing"))
        assertEquals(
            "attaches its state machine",
            describe("STATE_MACHINE_ATTACHED: State machine of the run configuration attached | stateMachine="),
        )
        assertFalse(EngineTrace.isNoise("[12:00:00.000] [env-1] [bulb] STATE_MACHINE_DETACHED: State machine 'glowing' detached | stateMachine=glowing"))
        assertFalse(EngineTrace.isNoise("[12:00:00.000] [env-1] [bulb] STATE_MACHINE_EXCHANGED: State machine 'glowing' exchanged for 'blinking'"))
    }

    @Test
    fun `details are split before each name, so values with commas such as paths and stacks stay whole`() {
        val line = checkNotNull(
            EngineTrace.parse(
                "[12:00:00.000] [env-1] [lamp] SPLIT_FIRED: off -> test bulb,test switch | path=0, starts=1,2, from=off, to=test bulb,test switch",
            ),
        )

        assertEquals(mapOf("path" to "0", "starts" to "1,2", "from" to "off", "to" to "test bulb,test switch"), line.details)
        assertEquals("split off -> test bulb, test switch into paths 1, 2", EngineTrace.describe(line))
        assertEquals(
            "join bulb ok, switch ok -> on from paths 1, 2",
            describe("JOIN_FIRED: bulb ok,switch ok -> on | ends=1,2, path=3, from=bulb ok,switch ok, to=on"),
        )
    }

    @Test
    fun `an exchange is one line that names the state machine that ended and the one that started`() {
        assertEquals(
            "exchanges glowing for blinking",
            describe("STATE_MACHINE_EXCHANGED: State machine 'glowing' exchanged for 'blinking' | ended=glowing, started=blinking, ends=0, path=1"),
        )
        assertEquals(
            "exchanges its state machine for blinking",
            describe(
                "STATE_MACHINE_EXCHANGED: State machine of the run configuration exchanged for 'blinking' | ended=, started=blinking, ends=1,2, path=3",
            ),
        )
    }

    @Test
    fun `read in order, the lines of a split state machine name their path until the join, and other machines never do`() {
        val lines = listOf(
            "[lamp] STATE_MACHINE_ATTACHED: State machine of the run configuration attached | stateMachine=, path=0",
            "[lamp] INITIAL_STATE: Initial state stack: idle | path=0, stack=idle",
            "[bulb] STATE_MACHINE_ATTACHED: State machine 'glowing' attached | stateMachine=glowing, path=0",
            "[lamp] TRANSITION_FIRED: idle -> off | path=0, from=idle, to=off, stack=off",
            "[lamp] SPLIT_FIRED: off -> test bulb,test switch | path=0, starts=1,2, from=off, to=test bulb,test switch",
            "[lamp] STATE_ENTRY_EXEC: Executing onEntry for state 'test bulb' | path=1, state=test bulb, stack=test bulb",
            "[bulb] TRANSITION_FIRED: dark -> lit | path=0, from=dark, to=lit, stack=lit",
            "[lamp] TRANSITION_FIRED: test switch -> check position | path=2, from=test switch, to=check position, stack=check position",
            "[lamp] JOIN_FIRED: bulb ok,switch ok -> on | ends=1,2, path=3, from=bulb ok,switch ok, to=on",
            "[lamp] STATE_ENTRY_EXEC: Executing onEntry for state 'on' | path=3, state=on, stack=on",
            "[lamp] SPLIT_FIRED: on -> a,b | path=3, starts=4,5, from=on, to=a,b",
            "[lamp] INITIAL_STATE: Forced state: off | ends=4,5, path=6, stack=off",
            "[lamp] TRANSITION_FIRED: off -> on | path=6, from=off, to=on, stack=on",
        ).mapIndexed { index, rest -> line(index.toLong(), OutputLevel.INFO, "[12:00:01.000] [env-1] $rest") }

        assertEquals(
            listOf(
                "lamp" to "attaches its state machine",
                "lamp" to "starts in idle",
                "bulb" to "attaches glowing",
                "lamp" to "idle -> off",
                "lamp" to "split off -> test bulb, test switch into paths 1, 2",
                "lamp" to "path 1: runs the entry actions of test bulb",
                "bulb" to "dark -> lit",
                "lamp" to "path 2: test switch -> check position",
                "lamp" to "join bulb ok, switch ok -> on from paths 1, 2",
                "lamp" to "runs the entry actions of on",
                "lamp" to "split on -> a, b into paths 4, 5",
                "lamp" to "set to off",
                "lamp" to "off -> on",
            ),
            StatechartLog.entries(lines).map { it.subject to it.text },
        )
        assertEquals("runs the entry actions of test bulb", StatechartLog.entry(lines[5]).text, "a line read on its own shows no path")
    }

    @Test
    fun `a split inside a split keeps naming paths until the last join, and a detached instance forgets its paths`() {
        val lines = listOf(
            "[lamp] SPLIT_FIRED: off -> a,b | path=0, starts=1,2, from=off, to=a,b",
            "[lamp] SPLIT_FIRED: a -> c,d | path=1, starts=3,4, from=a, to=c,d",
            "[lamp] JOIN_FIRED: c,d -> e | ends=3,4, path=5, from=c,d, to=e",
            "[lamp] TRANSITION_FIRED: e -> f | path=5, from=e, to=f, stack=f,b",
            "[lamp] JOIN_FIRED: f,b -> on | ends=5,2, path=6, from=f,b, to=on",
            "[lamp] SPLIT_FIRED: on -> a,b | path=6, starts=7,8, from=on, to=a,b",
            "[lamp] STATE_MACHINE_DETACHED: State machine 'blinking' detached | stateMachine=blinking, paths=7,8",
            "[lamp] STATE_MACHINE_ATTACHED: State machine 'blinking' attached | stateMachine=blinking, path=9",
            "[lamp] TRANSITION_FIRED: on -> off | path=9, from=on, to=off, stack=off",
        ).mapIndexed { index, rest -> line(index.toLong(), OutputLevel.INFO, "[12:00:01.000] [env-1] $rest") }

        assertEquals(
            listOf(
                "split off -> a, b into paths 1, 2",
                "path 1: split a -> c, d into paths 3, 4",
                "path 5: join c, d -> e from paths 3, 4",
                "path 5: e -> f",
                "join f, b -> on from paths 5, 2",
                "split on -> a, b into paths 7, 8",
                "detaches blinking",
                "attaches blinking",
                "on -> off",
            ),
            StatechartLog.entries(lines).map { it.text },
        )
    }

    /** The description of the trace line with the [rest] after the time, the environment and the machine. */
    private fun describe(rest: String): String = EngineTrace.describe(checkNotNull(EngineTrace.parse("[12:00:00.000] [env-1] [lamp] $rest")))

    private fun line(sequence: Long, level: OutputLevel, message: String) =
        OutputLine(sequence, Instant.parse("2026-11-12T12:41:30Z"), level, "stdout", message)
}
