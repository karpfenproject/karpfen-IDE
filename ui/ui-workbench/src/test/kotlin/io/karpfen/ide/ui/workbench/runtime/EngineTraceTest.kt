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
    fun `the lines of every tick are noise, the steps of a state machine are not`() {
        listOf(
            "[12:41:30.000] [env-1] [lamp] TICK_START: Tick #7 | state=off",
            "[12:41:30.001] [env-1] [lamp] STATE_DO_SKIP: onDo for state 'off' is empty \u2014 skipped | state=off",
            "[12:41:30.002] [env-1] [lamp] TICK_END: Tick #7 completed | state=off",
        ).forEach { assertTrue(EngineTrace.isTickNoise(it), it) }
        listOf(
            "[12:41:30.123] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on",
            "[12:41:30.124] [env-1] [lamp] ACTION_ERROR: Error in onEntry for state 'on': boom | state=on",
            "[WebSocket] Connection closed",
        ).forEach { assertFalse(EngineTrace.isTickNoise(it), it) }
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

    /** The description of the trace line with the [rest] after the time, the environment and the machine. */
    private fun describe(rest: String): String = EngineTrace.describe(checkNotNull(EngineTrace.parse("[12:00:00.000] [env-1] [lamp] $rest")))

    private fun line(sequence: Long, level: OutputLevel, message: String) =
        OutputLine(sequence, Instant.parse("2026-11-12T12:41:30Z"), level, "stdout", message)
}
