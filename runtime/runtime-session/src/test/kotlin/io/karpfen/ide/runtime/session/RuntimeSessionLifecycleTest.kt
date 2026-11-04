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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.api.RuntimeTimeouts
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployTiming
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.runtime.session.observation.SyncReason
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the lifecycle of a session after its deployment, end to end against the fake runtime, which answers only what
 * each test programs: a stop ends the connection, finalizes the model and records the environment once, and an event
 * sent after it never overtakes it; closing and shutting down in every state leave the ledger entry the state calls
 * for, and closing right when a stop begins still stops; a stop is bounded as the deploy timing says; the
 * environment's phase drives the state (halted from a snapshot, uncertain after a call without answer, lost after a
 * restart); a stop the session did not make is settled by reading the final model; a connection that gives up is a
 * warning; forcing a state reaches the runtime, a refusal comes back with the runtime's text, and events that got no
 * reaction are noted; and commands the state does not allow are refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSessionLifecycleTest {

    @TempDir
    lateinit var root: Path

    /** The states a test puts a session in. */
    enum class Situation { DEPLOYING, READY, RUNNING, HALTED, STOPPING, STOPPED, FAILED, CLOSED }

    @Test
    fun `a stop ends the connection, finalizes the model and records the environment once`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        programStop(rig, deployment, session)

        assertEquals(SessionCommandOutcome.Done, session.stop())

        assertEquals(SessionState.Stopped(session.progress.value.environment!!), session.state.value)
        assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentStopped), session.connection.value)
        assertEquals(SyncStatus.Final(3), session.model.value.sync)
        assertEquals(RuntimePhase.STOPPED, session.model.value.phase)
        assertEquals(ObservationMode.OFF, session.model.value.mode)
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
        assertIs<InjectionAvailability.Unavailable>(session.events.availability.value)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.STOPPED, 3)
        assertEquals(SessionCommandOutcome.Done, session.pull(), "a stopped environment can still be pulled")
        assertIs<SessionCommandOutcome.Refused>(session.start())
        session.close()
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
    }

    @Test
    fun `a stopped session stays stopped while the runtime still reports the engine's last tick as running`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        closeLinkOnStop(rig, session)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 3)

        assertEquals(SessionCommandOutcome.Done, session.stop())

        val stopped = SessionState.Stopped(session.progress.value.environment!!)
        assertEquals(stopped, session.state.value)
        assertEquals(SyncStatus.Final(3), session.model.value.sync)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 3)
        assertEquals(SessionCommandOutcome.Done, session.pull())
        assertEquals(stopped, session.state.value)
        val refused = SessionCommandOutcome.Refused(stopped, "Forcing the state of 'lamp' is not possible while the environment is stopped.")
        assertEquals(refused, session.forceState("lamp", "on"))
        assertIs<SessionCommandOutcome.Refused>(session.stop())
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SET_ACTIVE_STATE))
        assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertIs<InjectionAvailability.Unavailable>(session.events.availability.value)
        session.close()
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
    }

    @Test
    fun `a stopped session stays stopped while the engine's last tick holds the tick boundary, and a later pull finalizes it`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        closeLinkOnStop(rig, session)
        val noBoundary =
            FakeResponse.rejected(FakeResponse.CONFLICT, RuntimeText.TICK_BOUNDARY_TIMEOUT, mapOf("env" to "env-1", "ms" to "10000"))
        rig.fake.respond(RuntimeRoute.MODEL_SNAPSHOT, noBoundary)

        assertEquals(SessionCommandOutcome.Done, session.stop())

        val stopped = SessionState.Stopped(session.progress.value.environment!!)
        assertEquals(stopped, session.state.value)
        rig.fake.respond(RuntimeRoute.MODEL_SNAPSHOT, noBoundary)
        assertIs<SessionCommandOutcome.Refused>(session.pull())
        assertEquals(stopped, session.state.value)
        assertIs<SessionCommandOutcome.Refused>(session.forceState("lamp", "on"))
        assertIs<SessionCommandOutcome.Refused>(session.stop())
        rig.programSnapshot(deployment, "env-1", RuntimePhase.STOPPED, 4)
        assertEquals(SessionCommandOutcome.Done, session.pull())
        assertEquals(SyncStatus.Final(4), session.model.value.sync)
        assertEquals(stopped, session.state.value)
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.SET_ACTIVE_STATE))
        assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        session.close()
    }

    @Test
    fun `a stopped session stays stopped when the runtime forgets the environment afterwards`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        programStop(rig, deployment, session)
        assertEquals(SessionCommandOutcome.Done, session.stop())
        rig.fake.respond(
            RuntimeRoute.MODEL_SNAPSHOT,
            FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.UNKNOWN_ENVIRONMENT, mapOf("env" to "env-1")),
        )

        assertIs<SessionCommandOutcome.Failed>(session.pull())

        assertEquals(SessionState.Stopped(session.progress.value.environment!!), session.state.value)
        assertEquals(emptyList(), session.problems.value.filter { it.code == DeployProblemCodes.ENVIRONMENT_LOST })
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
        session.close()
    }

    @Test
    fun `an event sent after a stop never overtakes it`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        programStop(rig, deployment, session)

        val stopping = async(start = CoroutineStart.UNDISPATCHED) { session.stop() }
        val injected = session.events.inject(InjectionRequest("switch", "press", PayloadKind.NONE, ""))

        assertIs<InjectionOutcome.Unavailable>(injected)
        assertEquals(SessionCommandOutcome.Done, stopping.await())
        assertEquals(emptyList(), rig.fake.clientFrames)
    }

    @ParameterizedTest
    @EnumSource(Situation::class)
    fun `closing leaves the environment as it is and records it as the state calls for`(situation: Situation) = runTest {
        val rig = SessionRig(this, root)
        val session = sessionIn(situation, rig)

        session.close()
        advanceTimeBy(2.minutes)

        assertEquals(SessionState.Closed, session.state.value)
        assertIs<InjectionAvailability.Unavailable>(session.events.availability.value)
        val expected = when (situation) {
            Situation.DEPLOYING -> listOf(LedgerDisposition.LEFT_CONFIGURED)
            Situation.READY -> listOf(LedgerDisposition.LEFT_ACTIVATED)
            Situation.RUNNING -> listOf(LedgerDisposition.LEFT_RUNNING)
            Situation.HALTED -> listOf(LedgerDisposition.LEFT_HALTED)
            Situation.STOPPING, Situation.STOPPED, Situation.CLOSED -> listOf(LedgerDisposition.STOPPED)
            Situation.FAILED -> emptyList()
        }
        assertEquals(expected, rig.ledger.entries.value.map { it.disposition })
        val stops = if (situation == Situation.STOPPING || situation == Situation.STOPPED || situation == Situation.CLOSED) 1 else 0
        assertEquals(stops, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size, "closing never stops a deployed environment")
        assertTrue(rig.fake.links.none { it.isOpen }, "no link stays open")
        assertEquals(SessionCommandOutcome.Refused(SessionState.Closed, "The session is closed."), session.pull())
    }

    @ParameterizedTest
    @EnumSource(Situation::class)
    fun `shutting down stops what is activated, running or halted, then closes`(situation: Situation) = runTest {
        val rig = SessionRig(this, root)
        val session = sessionIn(situation, rig)

        session.shutdown()

        assertEquals(SessionState.Closed, session.state.value)
        val stopped = situation != Situation.FAILED
        assertEquals(if (stopped) 1 else 0, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertEquals(if (stopped) listOf(LedgerDisposition.STOPPED) else emptyList(), rig.ledger.entries.value.map { it.disposition })
        assertTrue(rig.fake.links.none { it.isOpen }, "no link stays open")
    }

    @Test
    fun `a snapshot that shows a halted engine makes the session halted, which only stops, switches off and pulls`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.HALTED, 2)

        assertEquals(SessionCommandOutcome.Done, session.pull())

        val halted = assertIs<SessionState.Halted>(session.state.value)
        assertEquals(1, session.problems.value.count { it.code == DeployProblemCodes.ENGINE_HALTED })
        assertIs<SessionCommandOutcome.Refused>(session.forceState("lamp", "on"))
        assertEquals(
            SessionCommandOutcome.Refused(halted, "Switching observation on is not possible while the environment's engine has halted."),
            session.setObservation(true),
        )
        assertEquals(SessionCommandOutcome.Done, session.setObservation(false))
        assertIs<InjectionAvailability.Unavailable>(session.events.availability.value)
        session.close()
    }

    @Test
    fun `a start without answer leaves the state as it was with a problem, and the next pull settles it`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        rig.programDeployment(deployment, start = false)
        val session = rig.launch(deployment, DeployOptions(start = false))
        val ready = assertIs<SessionState.Ready>(rig.awaitState(session) { it != SessionState.Deploying })
        rig.fake.hang(RuntimeRoute.START_ENVIRONMENT)
        rig.fake.hang(RuntimeRoute.MODEL_SNAPSHOT, where = mapOf(SessionRig.ENV_KEY to "env-1"))

        val started = assertIs<SessionCommandOutcome.Failed>(session.start())

        assertEquals(DeployProblemCodes.RUNTIME_NO_ANSWER, started.problem.code)
        assertEquals(ready, session.state.value)
        assertTrue(session.problems.value.any { it.code == DeployProblemCodes.RUNTIME_NO_ANSWER && it.severity == Severity.ERROR })
        rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 2, deployment.initialStacks)
        assertEquals(SessionCommandOutcome.Done, session.pull())
        assertIs<SessionState.Running>(session.state.value)
        session.close()
    }

    @Test
    fun `a runtime that restarted and forgot the environment fails the session as lost`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        val forgotten = FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.UNKNOWN_ENVIRONMENT, mapOf("env" to "env-1"))
        rig.fake.respond(RuntimeRoute.REGISTER_CLIENT_FOR_WEB_SOCKET, forgotten)
        rig.fake.respond(RuntimeRoute.LIST_ENVIRONMENTS, FakeResponse.ok("[]"))

        rig.linkOf(session).drop()
        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it is SessionState.Failed })

        assertEquals(DeployProblemCodes.ENVIRONMENT_LOST, failed.problem.code)
        assertEquals(listOf(LedgerDisposition.LOST), rig.ledger.entries.value.map { it.disposition })
        assertIs<ConnectionState.Ended>(session.connection.value)
        session.close()
        assertEquals(listOf(LedgerDisposition.LOST), rig.ledger.entries.value.map { it.disposition })
    }

    @Test
    fun `a stop the session did not make is settled by reading the final model`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.STOPPED, 4)

        rig.linkOf(session).closeByRuntime(WireNames.CLOSE_NORMAL, WireNames.REASON_ENVIRONMENT_STOPPED)
        assertIs<SessionState.Stopped>(rig.awaitState(session) { it is SessionState.Stopped })

        assertEquals(SyncStatus.Final(4), session.model.value.sync)
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT))
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
        session.close()
    }

    @Test
    fun `a connection that gives up reconnecting is a warning and the session keeps its state`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        val running = session.state.value
        repeat(ReconnectPolicy().maxAttempts) { rig.fake.refuseConnect(TransportFailure.Unreachable("Connection refused")) }

        rig.linkOf(session).drop()
        advanceTimeBy(10.minutes)

        assertEquals(running, session.state.value)
        val warning = session.problems.value.single { it.code == DeployProblemCodes.CONNECTION_LOST }
        assertEquals(Severity.WARNING, warning.severity)
        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), session.model.value.sync)
        assertIs<InjectionAvailability.Unavailable>(session.events.availability.value)
        session.close()
    }

    @Test
    fun `closing right when a stop begins still stops the environment and records it`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        programStop(rig, deployment, session)
        backgroundScope.launch {
            session.state.first { it is SessionState.Stopping }
            session.close()
        }
        runCurrent()

        val outcome = session.stop()
        advanceTimeBy(5.minutes)

        assertEquals(SessionCommandOutcome.Refused(SessionState.Closed, "The session is closed."), outcome)
        assertEquals(SessionState.Closed, session.state.value)
        assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
        assertTrue(rig.fake.links.none { it.isOpen }, "no link stays open")
    }

    @Test
    fun `a stop waits as long as the stop bound of the deploy timing allows`() = runTest {
        val rig = SessionRig(this, root, timing = DeployTiming(stopBound = 3.minutes), timeouts = RuntimeTimeouts(stop = 2.minutes))
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val session = rig.running(deployment)
        rig.fake.respond(RuntimeRoute.STOP_ENVIRONMENT, FakeResponse.ok(), delay = 100.seconds)
        programStop(rig, deployment, session)

        assertEquals(SessionCommandOutcome.Done, session.stop())

        assertIs<SessionState.Stopped>(session.state.value)
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
        assertTrue(session.problems.value.none { it.code == DeployProblemCodes.RUNTIME_NO_ANSWER }, "${session.problems.value}")
        session.close()
    }

    @Test
    fun `forcing a state reaches the runtime, its refusal comes back with the runtime's text, and unanswered events are noted`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        session.events.inject(InjectionRequest("switch", "press", PayloadKind.NONE, ""))

        assertEquals(SessionCommandOutcome.Done, session.forceState("lamp", "full power"))
        rig.fake.respond(
            RuntimeRoute.SET_ACTIVE_STATE,
            FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.UNKNOWN_STATE, mapOf("state" to "nowhere", "element" to "lamp")),
        )
        val refused = session.forceState("lamp", "nowhere")

        val forced = rig.fake.requests(RuntimeRoute.SET_ACTIVE_STATE).first()
        assertEquals("lamp" to "full power", forced.parameter("modelElement") to forced.parameter("state"))
        assertEquals(SessionCommandOutcome.Refused(session.state.value, "Unknown state 'nowhere' for model element 'lamp'"), refused)
        runCurrent()
        val notes = session.journal().filterIsInstance<SessionActivity.Note>().map { it.text }
        assertEquals(1, notes.count { "may have been discarded" in it }, "$notes")
        assertTrue(session.journal().any { it is SessionActivity.CallFailed && it.problem.code == DeployProblemCodes.RUNTIME_REJECTED })
        session.close()
    }

    @Test
    fun `commands the state does not allow are refused with the state and the reason`() = runTest {
        val rig = SessionRig(this, root)
        val session = rig.running(rig.prepared(SynthesizedModel.LAMP))
        val running = session.state.value

        assertEquals(SessionCommandOutcome.Refused(running, "Starting the environment is not possible while the environment runs."), session.start())
        assertEquals(emptyList(), rig.fake.requests(RuntimeRoute.START_ENVIRONMENT).drop(1))
        session.close()
    }

    /** Programs the answers of a stop of [session]: the runtime closes its link, and the final model at revision 3. */
    private fun programStop(rig: SessionRig, deployment: SessionRig.Deployment, session: RuntimeSession) {
        closeLinkOnStop(rig, session)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.STOPPED, 3)
    }

    /** Programs the runtime to close the open links of [session] after the next stop, as the runtime does. */
    private fun closeLinkOnStop(rig: SessionRig, session: RuntimeSession) {
        rig.fake.after(RuntimeRoute.STOP_ENVIRONMENT) {
            linksOf(session.clientId.value).filter { it.isOpen }.forEach { it.closeByRuntime(WireNames.CLOSE_NORMAL, WireNames.REASON_ENVIRONMENT_STOPPED) }
        }
    }

    /** A session of the lamp in [situation], with the answers its stop and shutdown need programmed. */
    private suspend fun TestScope.sessionIn(situation: Situation, rig: SessionRig): RuntimeSession {
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        return when (situation) {
            Situation.DEPLOYING -> {
                rig.fake.respond(RuntimeRoute.MODEL_SNAPSHOT, SessionRig.missingEnvironmentKey())
                rig.fake.respond(RuntimeRoute.SET_MODEL, FakeResponse.ok(), delay = 5.seconds)
                rig.programSnapshot(deployment, "env-1", RuntimePhase.ACTIVATED, 0)
                rig.programSnapshot(deployment, "env-1", RuntimePhase.RUNNING, 2, deployment.initialStacks)
                rig.launch(deployment).also {
                    advanceTimeBy(1.seconds)
                    programStop(rig, deployment, it)
                    assertEquals(SessionState.Deploying, it.state.value)
                }
            }
            Situation.READY -> {
                rig.programDeployment(deployment, start = false)
                rig.launch(deployment, DeployOptions(start = false)).also {
                    assertIs<SessionState.Ready>(rig.awaitState(it) { state -> state != SessionState.Deploying })
                    programStop(rig, deployment, it)
                }
            }
            Situation.RUNNING -> rig.running(deployment).also { programStop(rig, deployment, it) }
            Situation.HALTED -> rig.running(deployment).also {
                rig.programSnapshot(deployment, "env-1", RuntimePhase.HALTED, 2)
                it.pull()
                assertIs<SessionState.Halted>(it.state.value)
                programStop(rig, deployment, it)
            }
            Situation.STOPPING -> rig.running(deployment).also {
                rig.fake.respond(RuntimeRoute.STOP_ENVIRONMENT, FakeResponse.ok(), delay = 5.seconds)
                programStop(rig, deployment, it)
                backgroundScope.async { it.stop() }
                runCurrent()
                assertIs<SessionState.Stopping>(it.state.value)
            }
            Situation.STOPPED -> rig.running(deployment).also {
                programStop(rig, deployment, it)
                it.stop()
                assertIs<SessionState.Stopped>(it.state.value)
            }
            Situation.FAILED -> rig.launch(deployment).also {
                assertIs<SessionState.Failed>(rig.awaitState(it) { state -> state != SessionState.Deploying })
            }
            Situation.CLOSED -> rig.running(deployment).also {
                programStop(rig, deployment, it)
                it.shutdown()
            }
        }
    }
}
