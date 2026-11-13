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
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.EngineHalt
import io.karpfen.ide.runtime.protocol.message.EngineMessages
import io.karpfen.ide.runtime.protocol.message.HaltedTransitionKind
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.StateTiming
import io.karpfen.ide.runtime.protocol.message.TransitionTiming
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.PausePlace
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the debugger of a session, end to end against the fake runtime, which answers and pushes only what each test
 * programs.
 *
 * A session whose project has breakpoints switched on has a debugger. It follows the engine through the `engineStatus`
 * pushes of the runtime and shows it running, paused at a breakpoint or paused between two ticks. It asks the
 * runtime's feature `Breakpoint` for the status only once when the environment starts to run and once after a
 * reconnect, never at an interval and never after a command, and a read that a push overtook is dropped. Pausing,
 * resuming and stepping send their messages to `TickByTick`. A pause and a resume are noted in the journal, and the
 * way of a step to the end of its tick is not noted as a resume. Commands that do not fit the debugger's state are
 * refused without a call, a step the engine does not apply is refused with its answer, and a status that cannot be
 * read keeps the last state without a problem. A session without breakpoints has no debugger, and neither has a
 * session launched without one, which leaves `Breakpoint` off although the project has breakpoints on. While the
 * environment does not run the debugger is unavailable, pushes are ignored and the session refuses its commands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSessionDebuggerTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a session with breakpoints follows pushes into a pause at a breakpoint and out of it, and notes both`() = runTest {
        val rig = SessionRig(this, root)
        rig.status(RUNNING)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        runCurrent()
        assertTrue(session.debugger.isPresent)
        assertEquals(DebuggerState.Running, session.debugger.state.value)

        push(rig, session, HALT_AT_ENTRY)

        val halt = EngineHalt.AtState("lamp", "on", StateTiming.ENTRY)
        assertEquals(DebuggerState.Paused(PausePlace.AtBreakpoint(halt)), session.debugger.state.value)
        rig.tickByTick("execution resumed")
        assertEquals(SessionCommandOutcome.Done, session.debugger.resume())
        push(rig, session, RUNNING)
        assertEquals(DebuggerState.Running, session.debugger.state.value)
        assertEquals(listOf("resume"), rig.messagesTo(FeatureName.TICK_BY_TICK))
        assertEquals(listOf(EngineMessages.STATUS), rig.messagesTo(FeatureName.BREAKPOINT), "only the read at the start")
        assertEquals(listOf("Paused before the entry of the state on of lamp.", "Resumed."), session.debuggerNotes())
        session.close()
    }

    @Test
    fun `a step from a breakpoint pauses between two ticks without a resume in between, and a step not applied is refused`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        push(rig, session, HALT_AT_START)
        val halt = EngineHalt.AtTransition("lamp", HaltedTransitionKind.TRANSITION, listOf("off"), listOf("on"), 0, TransitionTiming.START)
        assertEquals(DebuggerState.Paused(PausePlace.AtBreakpoint(halt)), session.debugger.state.value)

        rig.tickByTick("execution is advancing 1 tick")
        assertEquals(SessionCommandOutcome.Done, session.debugger.step())
        push(rig, session, GOING_ON_FOR_TICKS)
        assertEquals(DebuggerState.Running, session.debugger.state.value)
        push(rig, session, BETWEEN_TICKS)
        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), session.debugger.state.value)

        rig.tickByTick(NOT_APPLIED)
        assertEquals(SessionCommandOutcome.Refused(session.state.value, NOT_APPLIED), session.debugger.step())
        assertEquals(listOf("tick:1", "tick:1"), rig.messagesTo(FeatureName.TICK_BY_TICK))
        runCurrent()
        assertEquals(listOf("Paused before the transition off to on of lamp.", "Paused between two ticks."), session.debuggerNotes())
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
        assertEquals(SessionCommandOutcome.Done, session.debugger.pause())
        push(rig, session, BETWEEN_TICKS)
        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), session.debugger.state.value)
        assertEquals(SessionCommandOutcome.Refused(running, "The run is paused already."), session.debugger.pause())
        assertEquals(listOf("pause"), rig.messagesTo(FeatureName.TICK_BY_TICK))
        session.close()
    }

    @Test
    fun `while only pushes arrive, the debugger asks for no status after the read at the start`() = runTest {
        val rig = SessionRig(this, root)
        rig.status(RUNNING)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        runCurrent()

        listOf(BETWEEN_TICKS, GOING_ON_FOR_TICKS, BETWEEN_TICKS, RUNNING, HALT_AT_ENTRY, RUNNING).forEach { status ->
            advanceTimeBy(1.minutes)
            push(rig, session, status)
        }
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(DebuggerState.Running, session.debugger.state.value)
        assertEquals(listOf(EngineMessages.STATUS), rig.messagesTo(FeatureName.BREAKPOINT))
        session.close()
    }

    @Test
    fun `an engine that already waits when the run starts shows paused from the read at the start`() = runTest {
        val rig = SessionRig(this, root)
        rig.status(HALT_AT_ENTRY)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        runCurrent()

        val halt = EngineHalt.AtState("lamp", "on", StateTiming.ENTRY)
        assertEquals(DebuggerState.Paused(PausePlace.AtBreakpoint(halt)), session.debugger.state.value)
        assertEquals(listOf("Paused before the entry of the state on of lamp."), session.debuggerNotes())
        session.close()
    }

    @Test
    fun `a read whose answer comes after a push is dropped, since the push is at least as new`() = runTest {
        val rig = SessionRig(this, root)
        rig.status(RUNNING, delay = 2.seconds)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP, DEBUGGED))
        runCurrent()

        push(rig, session, HALT_AT_ENTRY)
        advanceTimeBy(5.seconds)
        runCurrent()

        val halt = EngineHalt.AtState("lamp", "on", StateTiming.ENTRY)
        assertEquals(listOf(EngineMessages.STATUS), rig.messagesTo(FeatureName.BREAKPOINT), "the read was answered")
        assertEquals(DebuggerState.Paused(PausePlace.AtBreakpoint(halt)), session.debugger.state.value)
        session.close()
    }

    @Test
    fun `after a reconnect the debugger reads the status once, since a push sent meanwhile is lost`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP, DEBUGGED)
        rig.status(RUNNING)
        val session = rig.running(deployment)
        runCurrent()
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 2, deployment.initialStacks)
        rig.status(BETWEEN_TICKS)

        rig.linkOf(session).drop()
        advanceTimeBy(ReconnectPolicy().delayBefore(1) + 1.seconds)
        runCurrent()

        assertEquals(ConnectionState.Open(2), session.connection.value)
        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), session.debugger.state.value)
        assertEquals(listOf(EngineMessages.STATUS, EngineMessages.STATUS), rig.messagesTo(FeatureName.BREAKPOINT))
        session.close()
    }

    @Test
    fun `a status that cannot be read keeps the last state and raises no problem`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP, DEBUGGED)
        rig.status("""{"paused": "yes", "waiting": true}""")
        val session = rig.running(deployment)
        runCurrent()
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 2, deployment.initialStacks)
        push(rig, session, BETWEEN_TICKS)
        val paused = session.debugger.state.value
        val problems = session.problems.value

        rig.fake.respond(
            RuntimeRoute.SEND_MESSAGE_TO_FEATURE,
            FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.FEATURE_NOT_ACTIVE, mapOf("env" to "env-1", "feature" to "Breakpoint")),
            where = mapOf(FEATURE to FeatureName.BREAKPOINT.value),
        )
        rig.linkOf(session).drop()
        advanceTimeBy(ReconnectPolicy().delayBefore(1) + 1.seconds)
        runCurrent()

        assertEquals(DebuggerState.Paused(PausePlace.BetweenTicks), paused)
        assertEquals(paused, session.debugger.state.value)
        assertEquals(2, rig.messagesTo(FeatureName.BREAKPOINT).size, "read at the start and after the reconnect")
        assertEquals(problems, session.problems.value)
        val journal = session.journal()
        assertFalse(journal.any { it is SessionActivity.CallFailed || it is SessionActivity.ProblemRaised }, "$journal")
        session.close()
    }

    @Test
    fun `a session without breakpoints has no debugger, which refuses every command and asks nothing`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        push(rig, session, BETWEEN_TICKS)
        advanceTimeBy(10.minutes)
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
    fun `the debugger is unavailable and ignores pushes while the environment does not run, and its commands are refused`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP, DEBUGGED)
        rig.programDeployment(deployment, start = false)
        val session = rig.launch(deployment, DeployOptions(start = false))
        val ready = assertIs<SessionState.Ready>(rig.awaitState(session) { it != SessionState.Deploying })
        push(rig, session, HALT_AT_ENTRY)
        advanceTimeBy(10.minutes)
        runCurrent()

        assertTrue(session.debugger.isPresent)
        assertEquals(DebuggerState.Unavailable, session.debugger.state.value)
        assertEquals(
            SessionCommandOutcome.Refused(ready, "Pausing the run is not possible while the environment is ready to start."),
            session.debugger.pause(),
        )
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE))
        assertEquals(listOf(FeatureName.BREAKPOINT.value), rig.fake.requests(RuntimeRoute.ACTIVATE_FEATURE).map { it.parameter(FEATURE) })
        assertEquals(emptyList(), session.debuggerNotes())
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
        advanceTimeBy(10.minutes)
        runCurrent()

        assertFalse(session.debugger.isPresent)
        assertEquals(DebuggerState.Unavailable, session.debugger.state.value)
        assertFalse(FeatureName.BREAKPOINT in session.prepared.plan.features)
        val switchedOn = rig.fake.requests(RuntimeRoute.ACTIVATE_FEATURE).map { it.parameter(FEATURE) }
        assertFalse(FeatureName.BREAKPOINT.value in switchedOn, "$switchedOn")
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE))
        session.close()
    }

    /** Programs the next answer of the runtime's feature `Breakpoint` to be [body], given after [delay]. */
    private fun SessionRig.status(body: String, delay: Duration = Duration.ZERO) {
        fake.respond(
            RuntimeRoute.SEND_MESSAGE_TO_FEATURE,
            FakeResponse.ok(body),
            where = mapOf(FEATURE to FeatureName.BREAKPOINT.value),
            delay = delay,
        )
    }

    /** Programs the next answer of the runtime's feature `TickByTick` to be [body]. */
    private fun SessionRig.tickByTick(body: String) {
        fake.respond(RuntimeRoute.SEND_MESSAGE_TO_FEATURE, FakeResponse.ok(body), where = mapOf(FEATURE to FeatureName.TICK_BY_TICK.value))
    }

    /** Pushes the engine status [payload] to [session] through the fake of [rig] and lets the session take it. */
    private fun TestScope.push(rig: SessionRig, session: RuntimeSession, payload: String) {
        rig.linkOf(session).push(rig.engineStatus(session, payload))
        runCurrent()
    }

    /** The messages sent to the runtime's [feature] so far, in order. */
    private fun SessionRig.messagesTo(feature: FeatureName): List<String?> =
        fake.requests(RuntimeRoute.SEND_MESSAGE_TO_FEATURE).filter { it.parameter(FEATURE) == feature.value }.map { it.body }

    private companion object {
        /** The language features of a project whose sessions have a debugger. */
        val DEBUGGED: Set<KstatesFeature> = SessionRig.PLAIN_FEATURES + KstatesFeature.BREAKPOINTS

        /** The query parameter that names the feature. */
        const val FEATURE = "featureName"

        /** The status of an engine that runs. */
        val RUNNING = RuntimeWireWriter.engineStatusPayload(paused = false, waiting = false)

        /** The status of an engine that waits between two ticks. */
        val BETWEEN_TICKS = RuntimeWireWriter.engineStatusPayload(paused = true, waiting = true)

        /** The status of a paused engine that goes on for the ticks it was given. */
        val GOING_ON_FOR_TICKS = RuntimeWireWriter.engineStatusPayload(paused = true, waiting = false)

        /** The status of an engine halted at the entry of the lamp's state `on`. */
        val HALT_AT_ENTRY = RuntimeWireWriter.engineStatusPayload(true, true, RuntimeWireWriter.stateHalt("lamp", "entry", "on"))

        /** The status of an engine halted at the start of the lamp's first transition. */
        val HALT_AT_START = RuntimeWireWriter.engineStatusPayload(
            true,
            true,
            RuntimeWireWriter.transitionHalt(
                "lamp", "start", "Transition(fromState=off, toState=on)", "TRANSITION", listOf("off"), listOf("on"), 0,
            ),
        )

        /** The answer of `TickByTick` to a step it does not apply. */
        const val NOT_APPLIED = "execution is currently not paused, ticks will not be applied"
    }
}
