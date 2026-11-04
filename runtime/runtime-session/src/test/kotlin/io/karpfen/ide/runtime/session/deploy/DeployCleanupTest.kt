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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.PhaseKind
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.lifecycle.Uploads
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers what a failed or cancelled deployment leaves: a failure at every step with the cleanup of the phase it left
 * (nothing, stop and disconnect, or disconnect) and the ledger entry recorded; a creation without an answer; a lost
 * environment; an activation and a stop whose outcome is unknown; an injected disposer; and a cancellation at every
 * step, which cleans up and is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeployCleanupTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(FailingStep::class)
    fun `a failure at every step stops there and cleans up by the phase it left`(case: FailingStep) = runTest {
        val rig = DeployRig(this)
        case.prepare(rig.script)

        val failed = rig.deploy().failed()

        assertEquals(case.kind, failed.failure.step.kind)
        assertEquals(StepStatus.FAILED, failed.failure.step.status)
        assertEquals(case.code to Severity.ERROR, failed.failure.problem.code to failed.failure.problem.severity)
        val steps = rig.listener.last.steps
        val failedAt = steps.indexOfFirst { it.status == StepStatus.FAILED }
        assertEquals(failed.failure.step, steps[failedAt])
        assertTrue(steps.drop(failedAt + 1).all { it.status == StepStatus.PENDING }, "$steps")
        assertEquals(case.cleanup, rig.script.callsAfter(case.call))
        assertEquals(case.disposition, failed.cleanup.leftBehind?.disposition)
        assertEquals(listOfNotNull(failed.cleanup.leftBehind), rig.ledger.entries.value)
        failed.cleanup.leftBehind?.let { assertEquals(ENVIRONMENT, it.key) }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CancelledStep::class)
    fun `a cancellation at every step cleans up and is rethrown`(case: CancelledStep) = runTest {
        val rig = DeployRig(this)
        rig.script.hold(case.call)

        val deployment = async { rig.deploy() }
        runCurrent()
        assertEquals(case.kind, rig.listener.last.current?.kind)
        deployment.cancel()

        assertFailsWith<CancellationException> { deployment.await() }
        val cancelled = rig.listener.last.steps.single { it.status == StepStatus.FAILED }
        assertEquals(case.kind to "Cancelled", cancelled.kind to cancelled.detail)
        assertEquals(case.cleanup, rig.script.callsAfter(case.call))
        assertEquals(listOfNotNull(case.disposition), rig.ledger.entries.value.map { it.disposition })
    }

    @Test
    fun `a creation without an answer records that an empty environment may exist`() = runTest {
        val rig = DeployRig(this)
        rig.script.fail("createEnvironment", Failures.noAnswer(RuntimeRoute.CREATE_ENVIRONMENT))

        val failed = rig.deploy().failed()

        assertEquals(DeployProblemCodes.RUNTIME_NO_ANSWER, failed.failure.problem.code)
        val entry = assertNotNull(failed.cleanup.leftBehind)
        assertEquals(LedgerDisposition.UNKNOWN to null, entry.disposition to entry.key)
        assertEquals(listOf(entry), rig.ledger.entries.value)
        assertEquals(DeployProblemCodes.ENVIRONMENT_LEFT_BEHIND, entry.leftBehind()?.code)
        assertEquals(emptyList(), rig.script.callsAfter("createEnvironment"))
    }

    @Test
    fun `an environment the runtime forgot is disconnected and recorded as lost`() = runTest {
        val rig = DeployRig(this)
        rig.script.fail("setModel", Failures.unknownEnvironment(RuntimeRoute.SET_MODEL))

        val failed = rig.deploy().failed()

        assertEquals(DeployProblemCodes.ENVIRONMENT_LOST, failed.failure.problem.code)
        assertNull(failed.failure.problem.location)
        assertEquals(listOf("disconnect", "close"), rig.script.callsAfter("setModel"))
        val entry = assertNotNull(failed.cleanup.leftBehind)
        assertEquals(LedgerDisposition.LOST, entry.disposition)
        assertNull(entry.leftBehind())
    }

    @Test
    fun `an activation without an answer stops the environment whose phase is uncertain`() = runTest {
        val rig = DeployRig(this)
        val uncertain = EnvironmentPhase.Uncertain(setOf(PhaseKind.CONFIGURING, PhaseKind.ACTIVATED), "no answer", Uploads.NONE)
        rig.script.fail("activate", Failures.noAnswer(RuntimeRoute.RUN_ENVIRONMENT), phaseAfter = uncertain)

        val failed = rig.deploy().failed()

        assertEquals(DeployProblemCodes.RUNTIME_NO_ANSWER, failed.failure.problem.code)
        assertEquals(listOf("stop", "disconnect", "close"), rig.script.callsAfter("activate"))
        assertEquals(LedgerDisposition.STOPPED, failed.cleanup.leftBehind?.disposition)
    }

    @Test
    fun `a stop that fails during the cleanup records the phase it resolved`() = runTest {
        val rig = DeployRig(this)
        val uncertain = EnvironmentPhase.Uncertain(setOf(PhaseKind.ACTIVATED, PhaseKind.STOPPED), "no answer", Uploads.NONE)
        rig.script.fail("start", Failures.fault(RuntimeRoute.START_ENVIRONMENT, "Unexpected error"))
        rig.script.fail("stop", Failures.noAnswer(RuntimeRoute.STOP_ENVIRONMENT), phaseAfter = uncertain)

        val failed = rig.deploy().failed()

        assertEquals(DeployProblemCodes.RUNTIME_ERROR, failed.failure.problem.code)
        assertEquals(LedgerDisposition.UNKNOWN, failed.cleanup.leftBehind?.disposition)
        assertTrue(failed.cleanup.actions.first().startsWith("Stopping $ENVIRONMENT failed"), "${failed.cleanup.actions}")
    }

    @Test
    fun `the disposer decides what becomes of an environment that stays`() = runTest {
        val rig = DeployRig(this)
        rig.script.fail("setTickDelay", Failures.rejected(RuntimeRoute.SET_TICK_DELAY, RuntimeText.INVALID_PARAMETER, mapOf("name" to "milliseconds"), 400))
        val disposed = mutableListOf<Pair<RuntimeEnvironment, RuntimeEndpoint>>()
        val disposer = EnvironmentDisposer { environment, endpoint ->
            disposed += environment to endpoint
            LedgerEntry(endpoint, environment.key, LedgerDisposition.STOPPED, "Deleted by a disposer of the test.")
        }

        val failed = rig.deploy(disposer = disposer).failed()

        assertEquals(listOf<Pair<RuntimeEnvironment, RuntimeEndpoint>>(assertNotNull(rig.script.environment) to ENDPOINT), disposed)
        assertEquals("Deleted by a disposer of the test.", failed.cleanup.leftBehind?.note)
        assertEquals(emptyList(), rig.ledger.entries.value)
    }

    /**
     * A failure provoked at one step: the call that fails, how the doubles are set up for it, the problem code it
     * becomes, the calls of the cleanup and what the ledger records.
     */
    enum class FailingStep(
        val kind: DeployStepKind,
        val call: String,
        val code: String,
        val cleanup: List<String>,
        val disposition: LedgerDisposition?,
        private val arrange: (DeployScript) -> Unit,
    ) {
        CHECK_RUNTIME(
            DeployStepKind.CHECK_RUNTIME, "checkIdeSupport", DeployProblemCodes.RUNTIME_INCOMPATIBLE, emptyList(), null,
            { it.fail("checkIdeSupport", RuntimeFailure.UnexpectedStatus(RuntimeRoute.MODEL_SNAPSHOT, 404, "Not Found")) },
        ),
        CREATE(
            DeployStepKind.CREATE, "createEnvironment", DeployProblemCodes.RUNTIME_UNREACHABLE, emptyList(), null,
            { it.fail("createEnvironment", Failures.unreachable(RuntimeRoute.CREATE_ENVIRONMENT)) },
        ),
        CONNECT(
            DeployStepKind.CONNECT, "open", DeployProblemCodes.CONNECTION_LOST, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            { it.openAnswer = ConnectionState.Ended(ConnectionEnd.GaveUp(20, "Connection refused")) },
        ),
        UPLOAD_METAMODEL(
            DeployStepKind.UPLOAD_METAMODEL, "setMetamodel", DeployProblemCodes.RUNTIME_REJECTED, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            { it.fail("setMetamodel", Failures.refused(RuntimeRoute.SET_METAMODEL, "KMeta content does not define any types")) },
        ),
        UPLOAD_EVENTS(
            DeployStepKind.UPLOAD_EVENTS, "setEventDefinitions", DeployProblemCodes.RUNTIME_ERROR, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            { it.fail("setEventDefinitions", Failures.fault(RuntimeRoute.SET_EVENT_DEFINITIONS, "Unexpected error")) },
        ),
        UPLOAD_MODEL(
            DeployStepKind.UPLOAD_MODEL, "setModel", DeployProblemCodes.RUNTIME_REJECTED, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            {
                it.fail(
                    "setModel",
                    Failures.rejected(RuntimeRoute.SET_MODEL, RuntimeText.METAMODEL_REQUIRED, mapOf("env" to ENVIRONMENT.value)),
                )
            },
        ),
        ATTACH_STATE_MACHINE(
            DeployStepKind.ATTACH_STATE_MACHINE, "setStateMachine", DeployProblemCodes.RUNTIME_REJECTED, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            {
                it.fail(
                    "setStateMachine",
                    Failures.refused(RuntimeRoute.SET_STATE_MACHINE, "Exactly one state must be marked as INITIAL, but found 2."),
                )
            },
        ),
        SET_TICK_DELAY(
            DeployStepKind.SET_TICK_DELAY, "setTickDelay", DeployProblemCodes.RUNTIME_REJECTED, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            {
                it.fail(
                    "setTickDelay",
                    Failures.rejected(RuntimeRoute.SET_TICK_DELAY, RuntimeText.INVALID_PARAMETER, mapOf("name" to "milliseconds"), 400),
                )
            },
        ),
        SET_EVENT_TTL(
            DeployStepKind.SET_EVENT_TTL, "setEventTtl", DeployProblemCodes.RUNTIME_NO_ANSWER, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            { it.fail("setEventTtl", Failures.noAnswer(RuntimeRoute.SET_EVENT_TTL)) },
        ),
        LISTEN_DOMAINS(
            DeployStepKind.LISTEN_DOMAINS, "registerDomainListener", DeployProblemCodes.RUNTIME_REJECTED,
            listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED,
            {
                it.fail(
                    "registerDomainListener",
                    Failures.rejected(RuntimeRoute.REGISTER_DOMAIN_LISTENER, RuntimeText.MISSING_PARAMETER, mapOf("name" to "domain"), 400),
                )
            },
        ),
        ACTIVATE(
            DeployStepKind.ACTIVATE, "activate", DeployProblemCodes.RUNTIME_REJECTED, listOf("disconnect", "close"),
            LedgerDisposition.LEFT_CONFIGURED,
            {
                it.fail(
                    "activate",
                    Failures.rejected(
                        RuntimeRoute.RUN_ENVIRONMENT,
                        RuntimeText.UNKNOWN_ATTACHMENT,
                        mapOf("env" to ENVIRONMENT.value, "keys" to "wallSwitch"),
                    ),
                )
            },
        ),
        OBSERVE(
            DeployStepKind.OBSERVE, "switchOn", DeployProblemCodes.RUNTIME_REJECTED, listOf("stop", "disconnect", "close"),
            LedgerDisposition.STOPPED,
            {
                it.fail(
                    "switchOn",
                    Failures.rejected(
                        RuntimeRoute.SUBSCRIBE_MODEL,
                        RuntimeText.NO_SESSION,
                        mapOf("client" to CLIENT.value, "env" to ENVIRONMENT.value),
                    ),
                )
            },
        ),
        START(
            DeployStepKind.START, "start", DeployProblemCodes.RUNTIME_ERROR, listOf("stop", "disconnect", "close"),
            LedgerDisposition.STOPPED,
            { it.fail("start", Failures.fault(RuntimeRoute.START_ENVIRONMENT, "Unexpected error")) },
        ),
        CONFIRM_START(
            DeployStepKind.CONFIRM_START, "pull", DeployProblemCodes.ENGINE_HALTED, listOf("stop", "disconnect", "close"),
            LedgerDisposition.STOPPED,
            { it.pullAnswers += CallOutcome.Done(lampSnapshot(RuntimePhase.HALTED, emptyMap())) },
        ),
        ;

        /** Sets up [script] so that the step fails. */
        internal fun prepare(script: DeployScript) {
            arrange(script)
        }
    }

    /**
     * A cancellation at one step: the call held until the deployment is cancelled, the calls of the cleanup and what
     * the ledger records. A held activation or start completes on cancellation, as the real handle's does.
     */
    enum class CancelledStep(
        val kind: DeployStepKind,
        val call: String,
        val cleanup: List<String>,
        val disposition: LedgerDisposition?,
    ) {
        CHECK_RUNTIME(DeployStepKind.CHECK_RUNTIME, "health", emptyList(), null),
        CREATE(DeployStepKind.CREATE, "createEnvironment", emptyList(), LedgerDisposition.UNKNOWN),
        CONNECT(DeployStepKind.CONNECT, "open", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED),
        UPLOAD_METAMODEL(DeployStepKind.UPLOAD_METAMODEL, "setMetamodel", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED),
        UPLOAD_EVENTS(DeployStepKind.UPLOAD_EVENTS, "setEventDefinitions", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED),
        UPLOAD_MODEL(DeployStepKind.UPLOAD_MODEL, "setModel", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED),
        ATTACH_STATE_MACHINE(
            DeployStepKind.ATTACH_STATE_MACHINE, "setStateMachine", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED,
        ),
        SET_TICK_DELAY(DeployStepKind.SET_TICK_DELAY, "setTickDelay", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED),
        SET_EVENT_TTL(DeployStepKind.SET_EVENT_TTL, "setEventTtl", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED),
        LISTEN_DOMAINS(
            DeployStepKind.LISTEN_DOMAINS, "registerDomainListener", listOf("disconnect", "close"), LedgerDisposition.LEFT_CONFIGURED,
        ),
        ACTIVATE(DeployStepKind.ACTIVATE, "activate", listOf("stop", "disconnect", "close"), LedgerDisposition.STOPPED),
        OBSERVE(DeployStepKind.OBSERVE, "switchOn", listOf("stop", "disconnect", "close"), LedgerDisposition.STOPPED),
        START(DeployStepKind.START, "start", listOf("stop", "disconnect", "close"), LedgerDisposition.STOPPED),
        CONFIRM_START(DeployStepKind.CONFIRM_START, "pull", listOf("stop", "disconnect", "close"), LedgerDisposition.STOPPED),
    }
}
