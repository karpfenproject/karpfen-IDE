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
package io.karpfen.ide.app.headless

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostProblem
import io.karpfen.ide.runtime.host.HostProblemCode
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.DeployStepKind
import io.karpfen.ide.runtime.session.deploy.StepProgress
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.fixtures.FakeRuntimeSession
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.states.ActiveStatesReducer
import io.karpfen.ide.ui.workbench.runtime.RunPhase
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettingsFile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `run` through [HeadlessRun] over the IDE's own wiring in a [HeadlessRig]. The project is the synthesized lamp.
 * The host answers what each test programs, and each run launches a scriptable session that the test drives.
 *
 * The tests cover a run that the project's errors block, the choice of the descriptor and of the bridges with their
 * usage errors, the lines of a run from its start through its steps, its states and its bridges to its capture, a stop
 * that shuts the run down before the host, a run that never gets a debugger, and runs that end on their own.
 */
class HeadlessRunTest {

    @TempDir
    lateinit var temp: Path

    private val root: Path get() = temp.resolve("project").toAbsolutePath().normalize()

    private val descriptor: Path get() = root.resolve(SynthesizedModel.LAMP.descriptorName)

    private val settingsDirectory: Path get() = temp.resolve("settings")

    @Test
    fun `a project with errors prints its findings, does not run and ends with 1`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = root.resolve("lamp.kstates")
        Files.writeString(file, Files.readString(file).replaceFirst("STATEMACHINE", "STATMACHINE"))

        withRig { rig ->
            val status = rig.run(HeadlessCommand.Run(root), HeadlessStop())
            rig.settle()

            assertEquals(HeadlessExitCodes.ERRORS_FOUND, status.await())
            assertTrue(rig.output.any { it.startsWith("lamp.kstates:1: error: ") }, rig.output.toString())
            assertTrue("1 error" in rig.output)
            assertEquals(
                listOf(RunLines.BLOCKED_BY_ERRORS, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN),
                rig.output.takeLast(3),
            )
            assertEquals(emptyList(), rig.sessions.launched)
        }
    }

    @Test
    fun `a project without a descriptor, or with several and none chosen, is a usage error that names them`() = runTest(timeout = TIMEOUT) {
        lamp()
        Files.delete(descriptor)
        withRig { rig ->
            assertUsageError(rig, HeadlessCommand.Run(root), "the project has no environment descriptor (*.kenv.json)")
        }

        lamp()
        SynthesizedModels.copyTo(root.resolve("second"), SynthesizedModel.LAMP)
        withRig { rig ->
            assertUsageError(
                rig,
                HeadlessCommand.Run(root),
                "the project has several environment descriptors; choose one with --descriptor: second/lamp.kenv.json, lamp.kenv.json",
            )
        }
    }

    @Test
    fun `a descriptor or a bridge that is no such file of the project is a usage error that names the right ones`() = runTest(timeout = TIMEOUT) {
        lamp()
        bridge("door.kbridge.json")
        withRig { rig ->
            assertUsageError(
                rig,
                HeadlessCommand.Run(root, descriptor = root.resolve("lamp.kstates")),
                "lamp.kstates is no environment descriptor of the project; its environment descriptors are lamp.kenv.json",
            )
        }
        withRig { rig ->
            assertUsageError(
                rig,
                HeadlessCommand.Run(root, bridges = listOf(root.resolve("robot.kbridge.json"))),
                "robot.kbridge.json is no bridge file of the project; its bridge files are door.kbridge.json",
            )
        }
    }

    @Test
    fun `a chosen descriptor of several runs`() = runTest(timeout = TIMEOUT) {
        lamp()
        SynthesizedModels.copyTo(root.resolve("second"), SynthesizedModel.LAMP)
        withRig { rig ->
            val stop = HeadlessStop()
            val status = rig.run(HeadlessCommand.Run(root, descriptor = root.resolve("second/lamp.kenv.json")), stop)
            rig.settle()

            assertEquals("Running second/lamp.kenv.json", rig.output.first())
            assertEquals(root.resolve("second/lamp.kenv.json"), rig.sessions.launched.single().prepared.plan.descriptor)

            stop.request()
            rig.settle()
            assertEquals(HeadlessExitCodes.OK, status.await())
        }
    }

    @Test
    fun `a run prints its start, steps, phases, states, bridges and capture, and a stop shuts the run down before the host`() =
        runTest(timeout = TIMEOUT) {
            lamp(setOf(KstatesFeature.BREAKPOINTS))
            val projectFile = Files.readString(root.resolve(ProjectSettings.FILE_NAME))
            bridge("robot.kbridge.json")
            bridge("door.kbridge.json")
            withRig { rig ->
                val stop = HeadlessStop()
                val command = HeadlessCommand.Run(root, bridges = listOf(root.resolve("robot.kbridge.json")), capture = true)
                val status = rig.run(command, stop)
                rig.settle()

                val session = rig.sessions.launched.single()
                assertEquals(false, rig.sessions.options.single().debugger, "a headless run has no debugger, also with breakpoints on")
                assertEquals(listOf("Running lamp.kenv.json with robot.kbridge.json, captured to a file", "Docker: not started"), rig.output.take(2))
                assertTrue("Run lamp: ${RuntimeTexts.phase(RunPhase.DEPLOYING)}" in rig.output, rig.output.toString())
                assertTrue(RuntimeTexts.captured(captureFile(), root) in rig.output, rig.output.toString())
                assertTrue("robot.kbridge.json: failed. $ROBOT_PROBLEM" in rig.output, rig.output.toString())
                assertFalse(rig.output.any { "door.kbridge.json" in it }, "a bridge that was not chosen does not start")

                session.progressFlow.value = DeployProgress(listOf(CREATED, UPLOADING), ENVIRONMENT)
                rig.settle()
                session.progressFlow.value = DeployProgress(listOf(CREATED, UPLOADING.copy(status = StepStatus.DONE)), ENVIRONMENT)
                session.stateFlow.value = SessionState.Running(ENVIRONMENT)
                rig.settle()
                report(session, "lamp", listOf("on"))
                rig.settle()

                assertEquals(1, rig.output.count { it == RuntimeTexts.step(CREATED) }, "a step is printed once")
                assertTrue(RuntimeTexts.step(UPLOADING.copy(status = StepStatus.DONE)) in rig.output)
                assertFalse(RuntimeTexts.step(UPLOADING) in rig.output, "a running step is not printed")
                assertTrue("Run lamp: ${RuntimeTexts.phase(RunPhase.RUNNING)}" in rig.output)
                assertEquals("lamp: on", rig.output.last())
                assertEquals(emptyList(), rig.events)

                stop.request()
                rig.settle()

                assertEquals(HeadlessExitCodes.OK, status.await())
                assertEquals(listOf(RunLines.STOP_REQUESTED, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN), rig.output.takeLast(3))
                assertEquals(listOf("shutdown lamp", "close DOCKER"), rig.events, "the run shuts down before the host closes")
                assertEquals(emptyList(), rig.errors)
                assertEquals(projectFile, Files.readString(root.resolve(ProjectSettings.FILE_NAME)), "the project file stays")
                assertFalse(Files.exists(settingsDirectory), "the user's settings are not written")
            }
        }

    @Test
    fun `a runtime that cannot be started fails the run, which ends with 3`() = runTest(timeout = TIMEOUT) {
        lamp()
        withRig { rig ->
            rig.hosts.outcome = HostOutcome.Failed(HostProblem(HostProblemCode.ENGINE_UNAVAILABLE, "Docker is not running."))
            val status = rig.run(HeadlessCommand.Run(root), HeadlessStop())
            rig.settle()

            assertEquals(HeadlessExitCodes.FAILURE, status.await())
            assertTrue(rig.output.any { it.startsWith("The runtime could not be started: ") && "Docker is not running." in it }, rig.output.toString())
            assertEquals(listOf(RunLines.FAILED, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN), rig.output.takeLast(3))
            assertEquals(listOf("close DOCKER"), rig.events)
        }
    }

    @Test
    fun `a deployment that fails ends the run with 3, and so does an environment that stops on its own`() = runTest(timeout = TIMEOUT) {
        lamp()
        withRig { rig ->
            val status = rig.run(HeadlessCommand.Run(root), HeadlessStop())
            rig.settle()
            val session = rig.sessions.launched.single()
            session.stateFlow.value = SessionState.Failed(DEPLOY_FAILED, null)
            rig.settle()

            assertEquals(HeadlessExitCodes.FAILURE, status.await())
            assertEquals(listOf(RunLines.FAILED, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN), rig.output.takeLast(3))
        }
        withRig { rig ->
            val status = rig.run(HeadlessCommand.Run(root), HeadlessStop())
            rig.settle()
            val session = rig.sessions.launched.single()
            session.stateFlow.value = SessionState.Running(ENVIRONMENT)
            rig.settle()
            session.stateFlow.value = SessionState.Stopped(ENVIRONMENT)
            rig.settle()

            assertEquals(HeadlessExitCodes.FAILURE, status.await())
            assertEquals(listOf(RunLines.ENDED, RunLines.SHUTTING_DOWN, RunLines.SHUT_DOWN), rig.output.takeLast(3))
            assertEquals(listOf("shutdown lamp", "close DOCKER"), rig.events)
        }
    }

    /** Runs [block] with a new [HeadlessRig] and closes it afterwards. */
    private suspend fun TestScope.withRig(block: suspend (HeadlessRig) -> Unit) {
        val rig = HeadlessRig(testScheduler, settingsDirectory)
        try {
            block(rig)
        } finally {
            rig.close()
        }
    }

    /** Runs [command] in [rig] and asserts that it ends as a usage error with [message] and the usage. */
    private suspend fun assertUsageError(rig: HeadlessRig, command: HeadlessCommand.Run, message: String) {
        val status = rig.run(command, HeadlessStop())
        rig.settle()

        assertEquals(HeadlessExitCodes.USAGE, status.await())
        assertEquals(listOf("karpfen-ide: $message") + HeadlessUsage.TEXT.lines(), rig.errors)
        assertEquals(emptyList(), rig.output)
        assertEquals(emptyList(), rig.sessions.launched)
    }

    /**
     * Writes a fresh lamp into the project folder, replacing an earlier one, with a project file that switches
     * [features] on.
     */
    private fun lamp(features: Set<KstatesFeature> = emptySet()) {
        if (Files.exists(root)) root.toFile().deleteRecursively()
        SynthesizedModels.copyTo(root, SynthesizedModel.LAMP)
        Files.writeString(root.resolve(ProjectSettings.FILE_NAME), ProjectSettingsFile.format(ProjectSettings(languageFeatures = features)))
    }

    /** Writes a ROS bridge named [name] whose address no rosbridge server has, so it fails as soon as it starts. */
    private fun bridge(name: String) {
        Files.writeString(root.resolve(name), """{ "kind": "ros", "settings": { "url": "robot" } }""")
    }

    /** The one capture file of the project. */
    private fun captureFile(): Path =
        Files.list(root.resolve(WorkspaceOptions.RUN_CAPTURE_DIRECTORY)).use { it.toList() }.single()

    /** Lets the runtime report [stack] for the machine attached to [attachedTo] in [session]. */
    private fun report(session: FakeRuntimeSession, attachedTo: String, stack: List<String>) {
        val model = session.modelFlow.value
        val states = ActiveStatesReducer(session.prepared.layouts).reported(model.states, attachedTo, stack)
        session.modelFlow.value = model.copy(states = states)
    }

    private companion object {
        /** A bound for a test that hangs. It is no assertion on time. */
        val TIMEOUT = 60.seconds

        /** The environment of the runs. */
        val ENVIRONMENT = EnvironmentKey("env-1")

        /** Why a ROS bridge at the address `robot` fails. */
        const val ROBOT_PROBLEM = "The address \"robot\" is no address of a rosbridge server such as ws://localhost:9090."

        /** Why a deployment failed. */
        val DEPLOY_FAILED = DeployProblem(Severity.ERROR, "deploy.failed", "The environment could not be created.", null)

        /** A step that is done. */
        val CREATED = StepProgress(DeployStepKind.CREATE, "Creating the environment", StepStatus.DONE, null)

        /** A step that is running. */
        val UPLOADING = StepProgress(DeployStepKind.UPLOAD_METAMODEL, "Uploading lamp.kmeta", StepStatus.RUNNING, null)
    }
}
