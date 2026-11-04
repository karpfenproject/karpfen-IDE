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

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.workspace.document.OpenResult
import io.karpfen.ide.runtime.protocol.api.ActiveEnvironment
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.mirror.MirrorComparison
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.plan.DeployConfiguration
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.states.StackOrigin
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers deploying through a session against a real runtime: a deployment with the descriptor format's default
 * configuration runs both machines of the lamp, reports its steps in order, shows the reported stacks in place of the
 * predictions, is listed with its machines and serves the saved state machine; a dirty document is saved before the
 * deployment, so the runtime reads the edited value; a deployment without start can force a state and start; a stop is
 * final and running again deploys a fresh environment; and the realistic single robot reads back equal to the seed.
 *
 * Failures only a fake runtime can produce, an engine that halts at start and a runtime without IDE support, are
 * ordinary tests against the fake runtime.
 */
abstract class DeploymentScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `a deployment with the default configuration runs both machines, is listed and serves the saved state machine`() = runtime.run(root) {
        val descriptor = copy(SynthesizedModel.LAMP, DescriptorTiming.NONE)
        val prepared = prepare(open(descriptor), descriptor)

        val session = deploy(prepared)

        assertEquals(DeployConfiguration(tickDelayMs = 50, eventTtlMs = 3_000), prepared.plan.configuration)
        assertTrue(session.progress.value.steps.all { it.status == StepStatus.DONE }, "${session.progress.value}")
        assertEquals(
            session.progress.value.steps.map { it.kind },
            session.journal().filterIsInstance<SessionActivity.StepFinished>().map { it.step.kind },
            "the steps end in their order",
        )
        val model = session.awaitModel("both machines reported") { model ->
            model.stacks == mapOf("lamp" to listOf("off"), "wallSwitch" to listOf("down")) &&
                model.states.machines.values.all { it.origin == StackOrigin.REPORTED }
        }
        assertIs<SyncStatus.Live>(model.sync)
        val listed = assertIs<RuntimeResult.Ok<List<ActiveEnvironment>>>(client.activeEnvironments()).value
        assertEquals(setOf("lamp", "wallSwitch"), listed.single { it.key == session.environment }.modelElements.toSet())
        val source = call(RuntimeRoute.STATE_MACHINE_SOURCE, "envKey" to session.environment.value, "modelElement" to "lamp")
        assertEquals(Files.readString(descriptor.resolveSibling("lamp.kstates")), source.body)
    }

    @Test
    fun `an unsaved edit is saved before the deployment, and the runtime reads the edited value`() = runtime.run(root) {
        val descriptor = copy(SynthesizedModel.LAMP)
        val workspace = open(descriptor)
        val model = descriptor.resolveSibling("lamp.kmodel")
        val document = assertIs<OpenResult.Opened>(workspace.documents.open(model)).document
        val text = document.session.text
        val offset = text.snapshot.value.text.indexOf("\"off\"")
        text.replaceAll(text.snapshot.value.text.replaceRange(offset, offset + "\"off\"".length, "\"standby\""), EditOrigin.Typing)

        val session = deploy(prepare(workspace, descriptor), DeployOptions(start = false))

        assertTrue("\"standby\"" in Files.readString(model), "the edit was saved")
        assertEquals("standby", session.model.value.mirror.objectOf("lamp").text("mode"), "the runtime read the saved text back")
        assertTrue(session.problems.value.none { it.code == DeployProblemCodes.MODEL_DIFFERS }, "${session.problems.value}")
    }

    @Test
    fun `a deployment without start forces a state and then starts in it without running its entry`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.LAMP, options = DeployOptions(start = false))

        assertEquals(SessionCommandOutcome.Done, session.forceState("lamp", "full power"))
        assertEquals(SessionCommandOutcome.Done, session.start())
        session.awaitStack("lamp", listOf("on", "full power"))

        val pulled = session.pulled()
        assertEquals("off", pulled.mirror.objectOf("lamp").text("mode"))
        assertEquals(40.0, pulled.mirror.objectOf("bulb").number("watts"))
    }

    @Test
    fun `a stop is final, and running again deploys a fresh environment`() = runtime.run(root) {
        val first = deploy(SynthesizedModel.LAMP)

        assertEquals(SessionCommandOutcome.Done, first.stop())
        assertEquals(ConnectionState.Ended(ConnectionEnd.EnvironmentStopped), first.connection.value)
        assertIs<SyncStatus.Final>(first.model.value.sync)
        val second = deploy(SynthesizedModel.LAMP)

        assertNotEquals(first.environment, second.environment)
        val listed = assertIs<RuntimeResult.Ok<List<ActiveEnvironment>>>(client.activeEnvironments()).value.map { it.key }
        assertTrue(first.environment in listed && second.environment in listed, "$listed")
        assertEquals("stopped", snapshotOf(first.environment, first.prepared).phase)
    }

    @Test
    fun `the realistic single robot runs in its initial state and reads back equal to the seed`() = runtime.run(root) {
        val session = deploySingleRobot()

        session.awaitStack("turtle", listOf("ready"))

        assertTrue(session.problems.value.none { it.code == DeployProblemCodes.MODEL_DIFFERS }, "${session.problems.value}")
        assertEquals(emptyList(), MirrorComparison.differences(session.prepared.seed, session.snapshot().mirror), diagnostics)
    }
}
