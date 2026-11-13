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
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.protocol.catalogue.RejectedUpload
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.UploadError
import io.karpfen.ide.runtime.protocol.catalogue.UploadRejection
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.connection.ReconnectPolicy
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import io.karpfen.ide.runtime.session.deploy.DeployStepKind
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Covers a session whose deployment fails or is cancelled, end to end against the fake runtime, which answers only
 * what each test programs. The lamp deployed there has a history, so that its deployment switches on a runtime feature. A failure at any step fails the session with the step's problem. This happens after the
 * cleanup that the phase calls for, and the session carries the ledger entry of what stays. Closing the session at any
 * step cancels the deployment, and its cleanup still runs and records. A runtime without IDE support is refused before
 * anything is created. An engine that halts right after the start fails the deployment, which then stops the
 * environment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSessionFailureTest {

    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @EnumSource(DeployStepKind::class)
    fun `a failure at every step fails the session with the step's problem and the cleanup of its phase`(step: DeployStepKind) = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.preparedWithHistory()
        val case = failureAt(step, rig, deployment)

        val session = rig.launch(deployment)
        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it != SessionState.Deploying })

        assertEquals(listOf(case.code), failed.problems.map { it.code }, "the problem of a failure at $step: ${failed.problems} in ${session.progress.value.current}")
        assertEquals(case.leftBehind, failed.leftBehind?.disposition, "what stays after a failure at $step")
        assertEquals(listOfNotNull(case.leftBehind), rig.ledger.entries.value.map { it.disposition })
        assertEquals(step, session.progress.value.steps.single { it.status == StepStatus.FAILED }.kind)
        assertTrue(session.problems.value.containsAll(failed.problems), "${session.problems.value}")
        if (case.leftBehind != null && case.leftBehind != LedgerDisposition.UNKNOWN) {
            assertTrue(session.problems.value.any { it.code == DeployProblemCodes.ENVIRONMENT_LEFT_BEHIND }, "${session.problems.value}")
        }
        assertEquals(SessionCommandOutcome.Refused(failed, "Pulling the model is not possible while the session has failed."), session.pull())
        session.close()
    }

    @ParameterizedTest
    @EnumSource(DeployStepKind::class)
    fun `closing the session at every step cancels the deployment, whose cleanup runs and records what stays`(step: DeployStepKind) = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.preparedWithHistory()
        val expected = hangAt(step, rig, deployment)
        val session = rig.launch(deployment)
        advanceTimeBy(1.seconds)
        assertEquals(SessionState.Deploying, session.state.value)
        assertEquals(StepStatus.RUNNING, session.progress.value.current?.status)
        assertEquals(step, session.progress.value.current?.kind, "the deployment waits at $step: ${session.progress.value}")

        session.close()
        advanceTimeBy(5.minutes)

        assertEquals(SessionState.Closed, session.state.value)
        assertEquals(listOfNotNull(expected), rig.ledger.entries.value.map { it.disposition }, "what stays after closing at $step")
        if (expected == LedgerDisposition.STOPPED) assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertTrue(rig.fake.links.none { it.isOpen }, "no link stays open")
    }

    @Test
    fun `a model the runtime refuses for two errors fails the session with one problem at the place of each error`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val model = deployment.prepared.plan.model
        rig.fake.respond(RuntimeRoute.MODEL_SNAPSHOT, SessionRig.missingEnvironmentKey())
        val rejection = UploadRejection(
            RejectedUpload.Model,
            listOf(
                UploadError(2, 5, "Unknown relation 'lamb' of class type 'Room'", "kmodel.unknown-relation"),
                UploadError(4, 200, "'warm' is not a number, but prop 'watts' is of type 'number'", "kmodel.invalid-number"),
            ),
        )
        rig.fake.respond(RuntimeRoute.SET_MODEL, FakeResponse.rejected(rejection))

        val session = rig.launch(deployment)
        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it != SessionState.Deploying })

        val snapshot = model.snapshot
        assertEquals(
            listOf(
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused lamp.kmodel: Unknown relation 'lamb' of class type 'Room'",
                    SourceLocation(model.file, TextRange.empty(snapshot.offsetOf(1, 4)), snapshot),
                ),
                DeployProblem(
                    Severity.ERROR,
                    DeployProblemCodes.RUNTIME_REJECTED,
                    "The runtime refused lamp.kmodel: 'warm' is not a number, but prop 'watts' is of type 'number'",
                    SourceLocation(model.file, TextRange.empty(snapshot.lineRange(3).end), snapshot),
                ),
            ),
            failed.problems,
        )
        assertTrue(session.problems.value.containsAll(failed.problems), "${session.problems.value}")
        val step = session.progress.value.steps.single { it.status == StepStatus.FAILED }
        assertEquals(DeployStepKind.UPLOAD_MODEL to "The runtime refused lamp.kmodel with 2 errors.", step.kind to step.detail)
        assertEquals(listOf(LedgerDisposition.LEFT_CONFIGURED), rig.ledger.entries.value.map { it.disposition })
        session.close()
    }

    @Test
    fun `a handshake the runtime rejects after the deployment's checks fails the session and stops the environment`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        rig.programDeployment(deployment)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.STOPPED, 2)
        rig.fake.closeHandshake(WireNames.CLOSE_CANNOT_ACCEPT, WireNames.REASON_INVALID_CREDENTIALS)

        val session = rig.launch(deployment)
        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it is SessionState.Failed && it.leftBehind != null })

        assertEquals(DeployProblemCodes.HANDSHAKE_REJECTED, failed.problems.single().code)
        assertEquals(LedgerDisposition.STOPPED, failed.leftBehind?.disposition)
        assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
        assertEquals(SessionCommandOutcome.Refused(failed, "Starting the environment is not possible while the session has failed."), session.start())
        session.close()
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition }, "recorded once")
    }

    @Test
    fun `a runtime without IDE support is refused before anything is created`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)

        val session = rig.launch(deployment)
        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it != SessionState.Deploying })

        assertEquals(DeployProblemCodes.RUNTIME_INCOMPATIBLE, failed.problems.single().code)
        assertNull(failed.leftBehind)
        assertEquals(listOf(RuntimeRoute.HEALTH, RuntimeRoute.MODEL_SNAPSHOT), rig.fake.requests.map { it.route })
        assertEquals(emptyList(), rig.ledger.entries.value)
    }

    @Test
    fun `an engine that halts right after the start fails the deployment, which stops the environment`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        rig.programDeployment(deployment, start = false)
        rig.programSnapshot(deployment, "env-1", RuntimePhase.HALTED, 0)

        val session = rig.launch(deployment)
        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it != SessionState.Deploying })

        assertEquals(DeployProblemCodes.ENGINE_HALTED, failed.problems.single().code)
        assertEquals(LedgerDisposition.STOPPED, failed.leftBehind?.disposition)
        assertEquals(1, rig.fake.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        assertEquals(listOf(LedgerDisposition.STOPPED), rig.ledger.entries.value.map { it.disposition })
    }

    /**
     * A failure the test programs.
     *
     * @property code the code of the session's problem.
     * @property leftBehind what the ledger records, or null for nothing.
     */
    private data class Failure(val code: String, val leftBehind: LedgerDisposition?)

    /** Programs the answers of a deployment of [deployment] that fails at [step], and returns what follows. */
    private fun TestScope.failureAt(step: DeployStepKind, rig: SessionRig, deployment: SessionRig.Deployment): Failure {
        val fake = rig.fake
        val boom = FakeResponse(FakeResponse.SERVER_ERROR, "Unexpected error", FakeResponse.TEXT)
        if (step != DeployStepKind.CHECK_RUNTIME) fake.respond(RuntimeRoute.MODEL_SNAPSHOT, SessionRig.missingEnvironmentKey())
        if (step > DeployStepKind.OBSERVE) rig.programSnapshot(deployment, "env-1", RuntimePhase.ACTIVATED, 0)
        val configured = LedgerDisposition.LEFT_CONFIGURED
        return when (step) {
            DeployStepKind.CHECK_RUNTIME -> {
                fake.fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("Connection refused"))
                Failure(DeployProblemCodes.RUNTIME_UNREACHABLE, null)
            }
            DeployStepKind.CREATE -> {
                fake.respond(RuntimeRoute.CREATE_ENVIRONMENT, boom)
                Failure(DeployProblemCodes.RUNTIME_ERROR, null)
            }
            DeployStepKind.CONNECT -> {
                repeat(ReconnectPolicy().maxAttempts) { fake.refuseConnect(TransportFailure.Unreachable("Connection refused")) }
                Failure(DeployProblemCodes.CONNECTION_LOST, configured)
            }
            DeployStepKind.UPLOAD_METAMODEL -> {
                fake.respond(RuntimeRoute.SET_METAMODEL, boom)
                Failure(DeployProblemCodes.RUNTIME_ERROR, configured)
            }
            DeployStepKind.UPLOAD_EVENTS -> {
                fake.respond(RuntimeRoute.SET_EVENT_DEFINITIONS, FakeResponse(FakeResponse.BAD_REQUEST, "Something the client does not know", FakeResponse.TEXT))
                Failure(DeployProblemCodes.RUNTIME_REJECTED, configured)
            }
            DeployStepKind.UPLOAD_MODEL -> {
                fake.respond(RuntimeRoute.SET_MODEL, FakeResponse.rejected(SynthesizedModels.LAMP_UNKNOWN_TYPE_REJECTION))
                Failure(DeployProblemCodes.RUNTIME_REJECTED, configured)
            }
            DeployStepKind.ATTACH_STATE_MACHINE -> {
                fake.respond(RuntimeRoute.SET_STATE_MACHINE, boom, where = mapOf("attachedTo" to "wallSwitch"))
                Failure(DeployProblemCodes.RUNTIME_ERROR, configured)
            }
            DeployStepKind.SET_TICK_DELAY -> {
                fake.respond(RuntimeRoute.SET_TICK_DELAY, FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.INVALID_PARAMETER, mapOf("name" to "milliseconds")))
                Failure(DeployProblemCodes.RUNTIME_REJECTED, configured)
            }
            DeployStepKind.SET_EVENT_TTL -> {
                fake.respond(RuntimeRoute.SET_EVENT_TTL, FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.INVALID_PARAMETER, mapOf("name" to "ttlMs")))
                Failure(DeployProblemCodes.RUNTIME_REJECTED, configured)
            }
            DeployStepKind.LISTEN_DOMAINS -> {
                fake.respond(RuntimeRoute.REGISTER_DOMAIN_LISTENER, boom, where = mapOf("domain" to "switch"))
                Failure(DeployProblemCodes.RUNTIME_ERROR, configured)
            }
            DeployStepKind.SWITCH_FEATURES_ON -> {
                fake.respond(
                    RuntimeRoute.ACTIVATE_FEATURE,
                    FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.UNKNOWN_FEATURE, mapOf("feature" to "History")),
                )
                Failure(DeployProblemCodes.RUNTIME_REJECTED, configured)
            }
            DeployStepKind.ACTIVATE -> {
                fake.respond(
                    RuntimeRoute.RUN_ENVIRONMENT,
                    FakeResponse.rejected(FakeResponse.CONFLICT, RuntimeText.UNKNOWN_ATTACHMENT, mapOf("env" to "env-1", "keys" to "ghost")),
                )
                Failure(DeployProblemCodes.RUNTIME_REJECTED, configured)
            }
            DeployStepKind.OBSERVE -> {
                fake.respond(RuntimeRoute.SUBSCRIBE_MODEL, boom)
                Failure(DeployProblemCodes.RUNTIME_ERROR, LedgerDisposition.STOPPED)
            }
            DeployStepKind.START -> {
                fake.respond(RuntimeRoute.START_ENVIRONMENT, boom)
                Failure(DeployProblemCodes.RUNTIME_ERROR, LedgerDisposition.STOPPED)
            }
            DeployStepKind.CONFIRM_START -> {
                rig.programSnapshot(deployment, "env-1", RuntimePhase.HALTED, 0)
                Failure(DeployProblemCodes.ENGINE_HALTED, LedgerDisposition.STOPPED)
            }
        }
    }

    /** Programs a deployment of [deployment] that never gets past [step], and returns what closing leaves recorded. */
    private fun hangAt(step: DeployStepKind, rig: SessionRig, deployment: SessionRig.Deployment): LedgerDisposition? {
        val fake = rig.fake
        if (step != DeployStepKind.CHECK_RUNTIME) fake.respond(RuntimeRoute.MODEL_SNAPSHOT, SessionRig.missingEnvironmentKey())
        if (step > DeployStepKind.OBSERVE) rig.programSnapshot(deployment, "env-1", RuntimePhase.ACTIVATED, 0)
        val configured = LedgerDisposition.LEFT_CONFIGURED
        return when (step) {
            DeployStepKind.CHECK_RUNTIME -> null.also { fake.hang(RuntimeRoute.HEALTH) }
            DeployStepKind.CREATE -> LedgerDisposition.UNKNOWN.also { fake.hang(RuntimeRoute.CREATE_ENVIRONMENT) }
            DeployStepKind.CONNECT -> configured.also { fake.hangConnect() }
            DeployStepKind.UPLOAD_METAMODEL -> configured.also { fake.hang(RuntimeRoute.SET_METAMODEL) }
            DeployStepKind.UPLOAD_EVENTS -> configured.also { fake.hang(RuntimeRoute.SET_EVENT_DEFINITIONS) }
            DeployStepKind.UPLOAD_MODEL -> configured.also { fake.hang(RuntimeRoute.SET_MODEL) }
            DeployStepKind.ATTACH_STATE_MACHINE -> configured.also { fake.hang(RuntimeRoute.SET_STATE_MACHINE) }
            DeployStepKind.SET_TICK_DELAY -> configured.also { fake.hang(RuntimeRoute.SET_TICK_DELAY) }
            DeployStepKind.SET_EVENT_TTL -> configured.also { fake.hang(RuntimeRoute.SET_EVENT_TTL) }
            DeployStepKind.LISTEN_DOMAINS -> configured.also { fake.hang(RuntimeRoute.REGISTER_DOMAIN_LISTENER) }
            DeployStepKind.SWITCH_FEATURES_ON -> configured.also { fake.hang(RuntimeRoute.ACTIVATE_FEATURE) }
            DeployStepKind.ACTIVATE -> LedgerDisposition.STOPPED.also { fake.hang(RuntimeRoute.RUN_ENVIRONMENT) }
            DeployStepKind.OBSERVE -> LedgerDisposition.STOPPED.also { fake.hang(RuntimeRoute.SUBSCRIBE_MODEL) }
            DeployStepKind.START -> LedgerDisposition.STOPPED.also { fake.hang(RuntimeRoute.START_ENVIRONMENT) }
            DeployStepKind.CONFIRM_START -> LedgerDisposition.STOPPED.also { fake.hang(RuntimeRoute.MODEL_SNAPSHOT, where = mapOf(SessionRig.ENV_KEY to "env-1")) }
        }
    }
}
