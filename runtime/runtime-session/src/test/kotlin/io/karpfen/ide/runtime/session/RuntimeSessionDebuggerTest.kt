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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the debugger of a session, end to end against the fake runtime, which answers only what each test programs.
 *
 * A session whose project has breakpoints switched on has a debugger. It asks the runtime's feature `Breakpoint` for
 * the engine's status at an interval while the environment runs and right after each command, and shows the engine
 * running, paused at a breakpoint or paused between two ticks. Pausing, resuming and stepping send their messages to
 * `TickByTick`. A pause and a resume are noted in the journal. Commands that do not fit the debugger's state are
 * refused without a call, a step the engine does not apply is refused with its answer, and a status that cannot be
 * read keeps the last state without a problem. A session without breakpoints has no debugger, and neither has a
 * session launched without one, which leaves `Breakpoint` off although the project has breakpoints on. While the
 * environment does not run the debugger is unavailable and the session refuses its commands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSessionDebuggerTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a session with breakpoints follows the engine into a pause at a breakpoint and out of it, and notes both`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        runCurrent()
        assertTrue(session.debugger.isPresent)
        assertEquals(DebuggerState.Running, session.debugger.state.value)

        rig.status(HALT_AT_ENTRY)
        advanceTimeBy(INTERVAL)
        runCurrent()

        val halt = EngineHalt.AtState("lamp", "on", StateTiming.ENTRY)
        assertEquals(DebuggerState.Paused(PausePlace.AtBreakpoint(halt)), session.debugger.state.value)
        rig.tickByTick("execution resumed")
        rig.status(RUNNING)
        assertEquals(SessionCommandOutcome.Done, session.debugger.resume())
        assertEquals(DebuggerState.Running, session.debugger.state.value)
        assertEquals(listOf("resume"), rig.messagesTo(FeatureName.TICK_BY_TICK))
        assertTrue(rig.messagesTo(FeatureName.BREAKPOINT).all { it == """{"action": "getStatus"}""" })
        runCurrent()
        val notes = session.debuggerNotes()
        assertEquals(listOf("Paused before the entry of the state on of lamp.", "Resumed."), notes)
        session.close()
    }

    @Test
    fun `a step from a breakpoint pauses between two ticks, and a step the engine does not apply is refused with its answer`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        rig.status(HALT_AT_START)
        advanceTimeBy(INTERVAL)
        runCurrent()
        val halt = EngineHalt.AtTransition("lamp", HaltedTransitionKind.TRANSITION, listOf("off"), listOf("on"), 0, TransitionTiming.START)
        assertEquals(DebuggerState.Paused(PausePlace.AtBreakpoint(halt)), session.debugger.state.value)

        rig.tickByTick("execution is advancing 1 tick")
        rig.status(BETWEEN_TICKS)
        assertEquals(SessionCommandOutcome.Done, session.debugger.step())
        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), session.debugger.state.value)

        rig.tickByTick(NOT_APPLIED)
        assertEquals(SessionCommandOutcome.Refused(session.state.value, NOT_APPLIED), session.debugger.step())
        assertEquals(listOf("tick:1", "tick:1"), rig.messagesTo(FeatureName.TICK_BY_TICK))
        runCurrent()
        val notes = session.debuggerNotes()
        assertEquals(listOf("Paused before the transition off to on of lamp.", "Paused between two ticks."), notes)
        session.close()
    }

    @Test
    fun `pausing a running engine pauses it between two ticks, and commands that do not fit the state reach no runtime`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        runCurrent()
        val running = session.state.value

        assertEquals(SessionCommandOutcome.Refused(running, "The run is not paused."), session.debugger.resume())
        assertEquals(SessionCommandOutcome.Refused(running, "The run is not paused."), session.debugger.step())
        assertEquals(emptyList(), rig.messagesTo(FeatureName.TICK_BY_TICK))

        rig.tickByTick("execution paused")
        rig.status(BETWEEN_TICKS)
        assertEquals(SessionCommandOutcome.Done, session.debugger.pause())
        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), session.debugger.state.value)
        assertEquals(SessionCommandOutcome.Refused(running, "The run is paused already."), session.debugger.pause())
        assertEquals(listOf("pause"), rig.messagesTo(FeatureName.TICK_BY_TICK))
        session.close()
    }

    @Test
    fun `a status that cannot be read keeps the last state and raises no problem`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        rig.status(BETWEEN_TICKS)
        advanceTimeBy(INTERVAL)
        runCurrent()
        val paused = session.debugger.state.value
        val problems = session.problems.value

        rig.status("Error when parsing the message: broken")
        rig.status("""{"paused": "yes", "waiting": true}""")
        rig.fake.respond(
            RuntimeRoute.SEND_MESSAGE_TO_FEATURE,
            FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.FEATURE_NOT_ACTIVE, mapOf("env" to "env-1", "feature" to "Breakpoint")),
            where = mapOf(FEATURE to FeatureName.BREAKPOINT.value),
        )
        advanceTimeBy(INTERVAL * 4)
        runCurrent()

        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), paused)
        assertEquals(paused, session.debugger.state.value)
        assertEquals(problems, session.problems.value)
        assertFalse(session.journal().any { it is SessionActivity.CallFailed || it is SessionActivity.ProblemRaised }, "${session.journal()}")
        session.close()
    }

    @Test
    fun `a session without breakpoints has no debugger, which refuses every command and asks nothing`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        advanceTimeBy(INTERVAL * 4)
        runCurrent()

        assertFalse(session.debugger.isPresent)
        assertEquals(DebuggerState.Unavailable, session.debugger.state.value)
        val refused = assertIs<SessionCommandOutcome.Refused>(session.debugger.pause())
        assertTrue("breakpoints" in refused.reason, refused.reason)
        assertIs<SessionCommandOutcome.Refused>(session.debugger.resume())
        assertIs<SessionCommandOutcome.Refused>(session.debugger.step())
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE))
        session.close()
    }

    @Test
    fun `the debugger is unavailable while the environment does not run, and the session refuses its commands then`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP, DEBUGGED)
        rig.programDeployment(deployment, start = false)
        val session = rig.launch(deployment, DeployOptions(start = false))
        val ready = assertIs<SessionState.Ready>(rig.awaitState(session) { it != SessionState.Deploying })
        advanceTimeBy(INTERVAL * 4)
        runCurrent()

        assertTrue(session.debugger.isPresent)
        assertEquals(DebuggerState.Unavailable, session.debugger.state.value)
        assertEquals(
            SessionCommandOutcome.Refused(ready, "Pausing the run is not possible while the environment is ready to start."),
            session.debugger.pause(),
        )
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE))
        assertEquals(listOf(FeatureName.BREAKPOINT.value), rig.fake.requests(RuntimeRoute.ACTIVATE_FEATURE).map { it.parameter(FEATURE) })
        session.close()
    }

    @Test
    fun `a session launched without a debugger switches no Breakpoint on and has no debugger, also with breakpoints on`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP, DEBUGGED)
        assertTrue(FeatureName.BREAKPOINT in deployment.prepared.plan.features)
        rig.programDeployment(deployment)
        val session = rig.launch(deployment, DeployOptions(debugger = false))
        assertIs<SessionState.Running>(rig.awaitState(session) { it != SessionState.Deploying })
        advanceTimeBy(INTERVAL * 4)
        runCurrent()

        assertFalse(session.debugger.isPresent)
        assertEquals(DebuggerState.Unavailable, session.debugger.state.value)
        assertFalse(FeatureName.BREAKPOINT in session.prepared.plan.features)
        val switchedOn = rig.fake.requests(RuntimeRoute.ACTIVATE_FEATURE).map { it.parameter(FEATURE) }
        assertFalse(FeatureName.BREAKPOINT.value in switchedOn, "$switchedOn")
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE))
        session.close()
    }

    /** The notes of the debugger in this session's journal, in order. */
    private fun RuntimeSession.debuggerNotes(): List<String> =
        journal().filterIsInstance<SessionActivity.Note>().map { it.text }.filter { it.startsWith("Paused") || it == "Resumed." }

    /** Programs the next answer of the runtime's feature `Breakpoint` to be [body]. */
    private fun SessionRig.status(body: String) {
        fake.respond(RuntimeRoute.SEND_MESSAGE_TO_FEATURE, FakeResponse.ok(body), where = mapOf(FEATURE to FeatureName.BREAKPOINT.value))
    }

    /** Programs the next answer of the runtime's feature `TickByTick` to be [body]. */
    private fun SessionRig.tickByTick(body: String) {
        fake.respond(RuntimeRoute.SEND_MESSAGE_TO_FEATURE, FakeResponse.ok(body), where = mapOf(FEATURE to FeatureName.TICK_BY_TICK.value))
    }

    /** The messages sent to the runtime's [feature] so far, in order. */
    private fun SessionRig.messagesTo(feature: FeatureName): List<String?> =
        fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE).filter { it.parameter(FEATURE) == feature.value }.map { it.body }

    private companion object {
        /** The language features of a project whose sessions have a debugger. */
        val DEBUGGED: Set<KstatesFeature> = SessionRig.PLAIN_FEATURES + KstatesFeature.BREAKPOINTS

        /** The interval at which the debugger asks, by default. */
        val INTERVAL = RuntimeSessionOptions().debuggerInterval

        /** The query parameter that names the feature. */
        const val FEATURE = "featureName"

        /** The status of an engine that runs. */
        const val RUNNING = """{"paused":false,"waiting":false}"""

        /** The status of an engine that waits between two ticks. */
        const val BETWEEN_TICKS = """{"paused":true,"waiting":true}"""

        /** The status of an engine halted at the entry of the lamp's state `on`. */
        const val HALT_AT_ENTRY = """{"paused":true,"waiting":true,"halt":{"modelElementId":"lamp","timing":"entry","state":"on"}}"""

        /** The status of an engine halted at the start of the lamp's first transition. */
        const val HALT_AT_START = """{"paused":true,"waiting":true,"halt":{"modelElementId":"lamp","timing":"start",""" +
            """"transition":"Transition(fromState=off, toState=on)","kind":"TRANSITION","from":["off"],"to":["on"],"index":0}}"""

        /** The answer of `TickByTick` to a step it does not apply. */
        const val NOT_APPLIED = "execution is currently not paused, ticks will not be applied"
    }
}
