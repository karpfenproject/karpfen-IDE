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
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.connection.HandshakeRejection
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.plan.DeployConfiguration
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the deploy pipeline with doubles of the client, the environment handle, the connection and the observation.
 * It checks the steps in order with their titles and calls, that the configuration is always sent, and the progress
 * after every transition. It also checks skipped steps, the wait for the open connection before activating and
 * starting, observation on and off, and read-back differences as warnings. The start confirmation is checked for
 * success, halt, silence and its bounds on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeployPipelineTest {

    @Test
    fun `a deployment runs its steps in order, each titled by what it concerns`() = runTest {
        val rig = DeployRig(this)

        val deployed = rig.deploy().deployed()

        assertSame<RuntimeEnvironment?>(rig.script.environment, deployed.environment)
        assertEquals(CLIENT, deployed.connection.clientId)
        assertEquals(emptyList(), deployed.findings)
        assertEquals(
            listOf(
                DeployStepKind.CHECK_RUNTIME to "Checking the runtime at http://127.0.0.1:8080",
                DeployStepKind.CREATE to "Creating the environment",
                DeployStepKind.CONNECT to "Connecting to the environment",
                DeployStepKind.UPLOAD_METAMODEL to "Uploading lamp.kmeta",
                DeployStepKind.UPLOAD_EVENTS to "Uploading lamp-events.kmeta",
                DeployStepKind.UPLOAD_MODEL to "Uploading lamp.kmodel",
                DeployStepKind.ATTACH_STATE_MACHINE to "Attaching lamp.kstates to lamp",
                DeployStepKind.ATTACH_STATE_MACHINE to "Attaching switch.kstates to wallSwitch",
                DeployStepKind.SET_TICK_DELAY to "Setting the tick delay to 20 ms",
                DeployStepKind.SET_EVENT_TTL to "Setting the event time to live to 1500 ms",
                DeployStepKind.LISTEN_DOMAINS to "Listening to 2 event domains",
                DeployStepKind.ACTIVATE to "Activating the environment",
                DeployStepKind.OBSERVE to "Switching observation on",
                DeployStepKind.START to "Starting the environment",
                DeployStepKind.CONFIRM_START to "Confirming the start",
            ),
            rig.listener.last.steps.map { it.kind to it.title },
        )
        assertTrue(rig.listener.last.steps.all { it.status == StepStatus.DONE }, "${rig.listener.last}")
        assertEquals(ENVIRONMENT, rig.listener.last.environment)
    }

    @Test
    fun `each step makes its calls, the connection reported before it opens and waited for before activating and starting`() = runTest {
        val rig = DeployRig(this)

        rig.deploy().deployed()

        assertEquals(
            listOf(
                "health",
                "checkIdeSupport",
                "createEnvironment",
                "environmentCreated($ENVIRONMENT)",
                "connection($CLIENT)",
                "connected($CLIENT)",
                "open",
                "setMetamodel(text of lamp.kmeta)",
                "setEventDefinitions(text of lamp-events.kmeta)",
                "setModel(${LampPlans.MODEL_TEXT})",
                "setStateMachine(lamp, text of lamp.kstates)",
                "setStateMachine(wallSwitch, text of switch.kstates)",
                "setTickDelay(20)",
                "setEventTtl(1500)",
                "registerDomainListener($CLIENT, service)",
                "registerDomainListener($CLIENT, switch)",
                "awaitOpen(10s)",
                "activate",
                "switchOn",
                "differences(0)",
                "awaitOpen(10s)",
                "start",
                "pull",
            ),
            rig.script.log,
        )
    }

    @Test
    fun `the tick delay and the time to live are always sent, also when they are the defaults of the descriptor format`() = runTest {
        val rig = DeployRig(this)

        rig.deploy(LampPlans.plan(configuration = DeployConfiguration.DEFAULT)).deployed()

        assertEquals(listOf("setTickDelay(50)"), rig.script.callsOf("setTickDelay"))
        assertEquals(listOf("setEventTtl(3000)"), rig.script.callsOf("setEventTtl"))
    }

    @Test
    fun `the progress is published after every transition`() = runTest {
        val rig = DeployRig(this)

        rig.deploy().deployed()

        val published = rig.listener.published
        val first = published.first()
        assertTrue(first.steps.all { it.status == StepStatus.PENDING }, "$first")
        assertNull(first.environment)
        assertEquals(15, first.total)
        published.zipWithNext().forEach { (before, after) ->
            val changed = before.steps.indices.filter { before.steps[it] != after.steps[it] }
            assertTrue(changed.size == 1 || before.environment != after.environment, "one transition at a time: $before -> $after")
        }
        val listening = published
            .map { progress -> progress.steps.single { it.kind == DeployStepKind.LISTEN_DOMAINS } }
            .filter { it.status == StepStatus.RUNNING }
            .map { it.detail }
        assertEquals(listOf(null, "1 of 2: service", "2 of 2: switch"), listening)
        val statuses = published.map { progress -> progress.steps.first { it.kind == DeployStepKind.UPLOAD_MODEL }.status }.distinct()
        assertEquals(listOf(StepStatus.PENDING, StepStatus.RUNNING, StepStatus.DONE), statuses)
        assertEquals(15, rig.listener.last.completed)
        assertNull(rig.listener.last.current)
    }

    @Test
    fun `without event definitions, event domains or a start those steps are skipped`() = runTest {
        val rig = DeployRig(this)

        rig.deploy(LampPlans.plan(domains = emptyList(), withEvents = false), DeployOptions(start = false)).deployed()

        val skipped = rig.listener.last.steps.filter { it.status == StepStatus.SKIPPED }.map { it.kind }
        assertEquals(
            listOf(DeployStepKind.UPLOAD_EVENTS, DeployStepKind.LISTEN_DOMAINS, DeployStepKind.START, DeployStepKind.CONFIRM_START),
            skipped,
        )
        assertEquals(15, rig.listener.last.completed)
        assertEquals(emptyList(), rig.script.callsOf("setEventDefinitions") + rig.script.callsOf("registerDomainListener"))
        assertEquals(listOf("switchOn", "differences(0)"), rig.script.callsAfter("activate"))
    }

    @Test
    fun `with observation off the model is read back once instead of switching observation on`() = runTest {
        val rig = DeployRig(this)

        rig.deploy(options = DeployOptions(observe = false)).deployed()

        val observe = rig.listener.last.steps.single { it.kind == DeployStepKind.OBSERVE }
        assertEquals("Reading the model back", observe.title)
        assertEquals(emptyList(), rig.script.callsOf("switchOn"))
        assertEquals(listOf("pull", "differences(0)", "awaitOpen(10s)", "start", "pull"), rig.script.callsAfter("activate"))
    }

    @Test
    fun `the differences of the model read back become warnings of the result`() = runTest {
        val rig = DeployRig(this)
        val difference = DeployProblem(
            Severity.WARNING,
            DeployProblemCodes.MODEL_DIFFERS,
            "The runtime read 'spare' without its property 'watts'.",
            LampPlans.locations.objectIn(LampPlans.modelFile, "spare"),
        )
        rig.script.differences = listOf(difference)

        val deployed = rig.deploy().deployed()

        assertEquals(listOf(difference), deployed.findings)
        assertEquals("1 object differs", rig.listener.last.steps.single { it.kind == DeployStepKind.OBSERVE }.detail)
    }

    @Test
    fun `an activation waits for the open connection and fails with a lost connection when the wait passes`() = runTest {
        val rig = DeployRig(this)
        rig.script.awaitOpenAnswers += null

        val started = currentTime
        val failed = rig.deploy(timing = DeployTiming(connectionWait = 4.seconds)).failed()

        assertEquals(4_000, currentTime - started)
        assertEquals(DeployStepKind.ACTIVATE, failed.failure.step.kind)
        assertEquals(DeployProblemCodes.CONNECTION_LOST to Severity.ERROR, failed.failure.problem.code to failed.failure.problem.severity)
        assertEquals(emptyList(), rig.script.callsOf("activate"))
    }

    @Test
    fun `a handshake rejected before the activation fails it`() = runTest {
        val rig = DeployRig(this)
        rig.script.awaitOpenAnswers += ConnectionState.Ended(ConnectionEnd.HandshakeRejected(HandshakeRejection.InvalidCredentials))

        val failed = rig.deploy().failed()

        assertEquals(DeployStepKind.ACTIVATE, failed.failure.step.kind)
        assertEquals(DeployProblemCodes.HANDSHAKE_REJECTED, failed.failure.problem.code)
        assertTrue("Invalid credentials" in failed.failure.problem.message, failed.failure.problem.message)
        assertEquals(listOf("disconnect", "close"), rig.script.callsAfter("awaitOpen"))
        assertEquals(LedgerDisposition.LEFT_CONFIGURED, failed.cleanup.leftBehind?.disposition)
    }

    @Test
    fun `a handshake rejected before the start fails it and stops the activated environment`() = runTest {
        val rig = DeployRig(this)
        rig.script.awaitOpenAnswers += ConnectionState.Open(1)
        rig.script.awaitOpenAnswers += ConnectionState.Ended(ConnectionEnd.HandshakeRejected(HandshakeRejection.Other(1003, "Invalid auth format")))

        val failed = rig.deploy().failed()

        assertEquals(DeployStepKind.START, failed.failure.step.kind)
        assertEquals(DeployProblemCodes.HANDSHAKE_REJECTED, failed.failure.problem.code)
        assertEquals(emptyList(), rig.script.callsOf("start"))
        assertEquals(listOf("stop", "disconnect", "close"), rig.script.callsAfter("awaitOpen"))
        assertEquals(LedgerDisposition.STOPPED, failed.cleanup.leftBehind?.disposition)
    }

    @Test
    fun `a connection that gives up while opening fails the connect step`() = runTest {
        val rig = DeployRig(this)
        rig.script.openAnswer = ConnectionState.Ended(ConnectionEnd.GaveUp(20, "Connection refused"))

        val failed = rig.deploy().failed()

        assertEquals(DeployStepKind.CONNECT, failed.failure.step.kind)
        assertEquals(DeployProblemCodes.CONNECTION_LOST, failed.failure.problem.code)
        assertNull(failed.failure.failure)
        assertEquals(emptyList(), rig.script.callsOf("setMetamodel"))
    }

    @Test
    fun `the start is confirmed once every machine reports a state, reading once a poll`() = runTest {
        val rig = DeployRig(this)
        rig.script.pullAnswers += CallOutcome.Done(lampSnapshot(RuntimePhase.RUNNING, mapOf("lamp" to listOf("off"))))

        val deployed = rig.deploy().deployed()

        assertEquals(emptyList(), deployed.findings)
        assertEquals(listOf(250L), rig.script.pullTimes.zipWithNext { a, b -> b - a })
        val confirm = rig.listener.last.steps.single { it.kind == DeployStepKind.CONFIRM_START }
        assertEquals(StepStatus.DONE, confirm.status)
    }

    @Test
    fun `a failed read during the confirmation is read again`() = runTest {
        val rig = DeployRig(this)
        rig.script.pullAnswers += CallOutcome.Failed(Failures.noAnswer(RuntimeRoute.MODEL_SNAPSHOT))

        val deployed = rig.deploy().deployed()

        assertEquals(emptyList(), deployed.findings)
        assertEquals(2, rig.script.pullTimes.size)
    }

    @Test
    fun `an engine that halted right after the start fails the confirmation`() = runTest {
        val rig = DeployRig(this)
        rig.script.pullAnswers += CallOutcome.Done(lampSnapshot(RuntimePhase.HALTED, emptyMap()))

        val failed = rig.deploy().failed()

        assertEquals(DeployStepKind.CONFIRM_START, failed.failure.step.kind)
        assertEquals(DeployProblemCodes.ENGINE_HALTED to Severity.ERROR, failed.failure.problem.code to failed.failure.problem.severity)
        assertEquals(listOf("stop", "disconnect", "close"), rig.script.callsAfter("pull"))
        assertEquals(LedgerDisposition.STOPPED, failed.cleanup.leftBehind?.disposition)
    }

    @Test
    fun `a machine silent until the bound passes is a warning and the deployment still succeeds`() = runTest {
        val rig = DeployRig(this)
        repeat(100) { rig.script.pullAnswers += CallOutcome.Done(lampSnapshot(RuntimePhase.RUNNING, mapOf("lamp" to listOf("off")))) }

        val deployed = rig.deploy().deployed()

        val warning = deployed.findings.single()
        assertEquals(DeployProblemCodes.ENGINE_SILENT to Severity.WARNING, warning.code to warning.severity)
        assertTrue("'wallSwitch'" in warning.message && "'lamp'" !in warning.message, warning.message)
        assertEquals(3_000, currentTime - rig.script.pullTimes.first())
        assertTrue(rig.script.pullTimes.zipWithNext { a, b -> b - a }.all { it == 250L }, "${rig.script.pullTimes}")
        assertEquals(StepStatus.DONE, rig.listener.last.steps.single { it.kind == DeployStepKind.CONFIRM_START }.status)
    }

    @Test
    fun `a long tick delay stretches the poll and the bound of the confirmation`() = runTest {
        val rig = DeployRig(this)
        repeat(100) { rig.script.pullAnswers += CallOutcome.Done(lampSnapshot(RuntimePhase.RUNNING, emptyMap())) }

        val deployed = rig.deploy(LampPlans.plan(configuration = DeployConfiguration(500, 1500))).deployed()

        assertEquals(DeployProblemCodes.ENGINE_SILENT, deployed.findings.single().code)
        assertEquals(10_000, currentTime - rig.script.pullTimes.first())
        assertTrue(rig.script.pullTimes.zipWithNext { a, b -> b - a }.all { it == 500L }, "${rig.script.pullTimes}")
    }

    @Test
    fun `the timing refuses waits that cannot work`() {
        assertFailsWith<IllegalArgumentException> { DeployTiming(connectionWait = 0.milliseconds) }
        assertFailsWith<IllegalArgumentException> { DeployTiming(confirmPoll = 0.milliseconds) }
        assertFailsWith<IllegalArgumentException> { DeployTiming(confirmMinimum = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { DeployTiming(confirmTicks = -1) }
    }

    @Test
    fun `the progress names the running or failed step and counts the finished ones`() {
        val steps = listOf(
            StepProgress(DeployStepKind.CHECK_RUNTIME, "Checking", StepStatus.DONE, null),
            StepProgress(DeployStepKind.CREATE, "Creating", StepStatus.SKIPPED, null),
            StepProgress(DeployStepKind.CONNECT, "Connecting", StepStatus.FAILED, "lost"),
            StepProgress(DeployStepKind.UPLOAD_METAMODEL, "Uploading", StepStatus.PENDING, null),
        )

        val progress = DeployProgress(steps, ENVIRONMENT)

        assertEquals(steps[2], progress.current)
        assertEquals(2, progress.completed)
        assertEquals(4, progress.total)
    }

    @Test
    fun `a located problem keeps its range within its text`() {
        val location = LampPlans.locations.objectIn(LampPlans.modelFile, "bulb")

        assertEquals(SourceLocation(LampPlans.modelFile, location?.range, LampPlans.modelSnapshot), location)
        assertEquals("bulb", location?.range?.let { LampPlans.MODEL_TEXT.substring(it.start, it.end) })
    }
}
