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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostProblem
import io.karpfen.ide.runtime.host.HostProblemCode
import io.karpfen.ide.runtime.host.LogStream
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import io.karpfen.ide.runtime.protocol.golden.RuntimeWireWriter
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.fixtures.ModelWireText
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the runtime state of a workspace. The host is a
 * [FakeRuntimeHost][io.karpfen.ide.ui.workbench.testing.FakeRuntimeHost] that answers what each test programs. The
 * sessions are real, and their clients reach the fake runtime, which also answers only what each test programs.
 *
 * The tests cover which descriptor Run picks, saving before preparing, and blocked and failed runs. They follow a
 * launch until the environment runs. They run again, also while the previous run waits for the runtime. They cover
 * stopping, replacing the host when the settings change, and closing the workspace. And they check the bound of the
 * sessions' shutdown and the limit of the runs list. And they capture a run to a file and end the capture with the
 * run.
 */
class RuntimeStateTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val settingsDirectory: Path get() = dir.resolve("settings")

    @Test
    fun `a given descriptor runs as it is`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        val metronome = copy(SynthesizedModel.METRONOME)
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST
            openEditor(lamp)

            confirmRun(metronome).joinSettled()

            assertEquals(metronome, runtime.runs.value.single().descriptor)
            assertNoDialog()
        }
    }

    @Test
    fun `without a given descriptor, the active editor's descriptor runs`() = runTest(timeout = TIMEOUT) {
        copy(SynthesizedModel.LAMP)
        val metronome = copy(SynthesizedModel.METRONOME)
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST
            openEditor(metronome)

            confirmRun().joinSettled()

            assertEquals(metronome, runtime.runs.value.single().descriptor)
        }
    }

    @Test
    fun `the only descriptor of the workspace runs without asking`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST

            confirmRun().joinSettled()

            assertEquals(lamp, runtime.runs.value.single().descriptor)
            assertEquals("lamp", runtime.runs.value.single().planName)
        }
    }

    @Test
    fun `among several descriptors the user chooses, and the choice is preselected the next time`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        val metronome = copy(SynthesizedModel.METRONOME)
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST

            val first = runtime.run()
            val asked = answer(DialogRequest.ChooseDescriptor::class, DialogResponse.Chosen(1))
            answerRun()

            assertTrue(first.isCompleted)
            assertEquals(listOf(lamp, metronome), asked.descriptors)
            assertEquals(0, asked.preselected)
            assertEquals(metronome, runtime.runs.value.single().descriptor)

            val second = runtime.run()
            val again = answer(DialogRequest.ChooseDescriptor::class, DialogResponse.Cancel)

            assertTrue(second.isCompleted)
            assertEquals(1, again.preselected, "the last choice is preselected")
            assertEquals(1, runtime.runs.value.size, "a cancelled choice runs nothing")
        }
    }

    @Test
    fun `without any descriptor an error dialog says how to create one`() = runTest(timeout = TIMEOUT) {
        Files.createDirectories(root)
        withWorkbench(settingsDirectory) {
            open(root)

            val run = runtime.run()
            val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)

            assertTrue(run.isCompleted)
            assertEquals(DescriptorResolver.NO_DESCRIPTOR_TITLE, error.title)
            assertTrue("New Environment File" in error.message, error.message)
            assertTrue(runtime.runs.value.isEmpty())
            assertEquals(0, runtimeHosts.current.starts)
        }
    }

    @Test
    fun `Run saves every open file before it prepares, and a failed save stops the run`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        val model = lamp.resolveSibling("lamp.kmodel")
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST
            val editor = openEditor(model)
            editor.session.text.apply(listOf(TextEdit.insert(0, "\n")), EditOrigin.Typing)
            settle()
            fileStore.failingWrites.add(model)

            val failed = confirmRun(lamp)
            answer(DialogRequest.Error::class, DialogResponse.Confirm)

            assertTrue(failed.isCompleted)
            assertTrue(runtime.runs.value.isEmpty(), "a failed save runs nothing")
            assertTrue(document(model).isDirty.value)

            fileStore.failingWrites.clear()
            confirmRun(lamp).joinSettled()

            assertFalse(document(model).isDirty.value, "saved before the run")
            assertTrue(Files.readString(model).startsWith("\n"))
            assertEquals(RunPhase.FAILED, runtime.runs.value.single().phase)
        }
    }

    @Test
    fun `a blocked preparation lists its findings and never asks for the runtime`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        Files.writeString(lamp, Files.readString(lamp).replace("\"attachedTo\": \"lamp\"", "\"attachedTo\": \"ghost\""))
        withWorkbench(settingsDirectory) {
            open(root)

            confirmRun(lamp).joinSettled()

            val entry = runtime.runs.value.single()
            assertEquals(RunPhase.BLOCKED, entry.phase)
            assertTrue(entry.findings.any { it.severity == Severity.ERROR }, "${entry.findings}")
            assertEquals(0, runtimeHosts.current.starts)
        }
    }

    @Test
    fun `a runtime that cannot start fails the run with its problem`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST

            confirmRun(lamp).joinSettled()

            val entry = runtime.runs.value.single()
            assertEquals(RunPhase.FAILED, entry.phase)
            assertEquals(FAILED_HOST.problem, entry.hostProblem)
            assertEquals(1, runtimeHosts.current.starts)
        }
    }

    @Test
    fun `a launch follows the session until the environment runs`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            programDeployment(lamp, ENV_1)

            confirmRun(lamp).joinSettled()

            val entry = runtime.runs.value.single()
            assertEquals(RunPhase.RUNNING, entry.phase)
            assertEquals(EnvironmentKey(ENV_1), entry.environment)
            assertEquals(entry, runtime.selected.value)
            assertTrue(state.layout.state.value.toolWindow(ToolWindowId.RUNTIME).visible, "the Runtime tool window shows the run")
            val session = assertNotNull(entry.session)
            assertIs<SessionState.Running>(session.state.value)
            assertEquals(ObservationMode.ON, session.model.value.mode, "the deployment observes")
            assertNotNull(runtime.details(entry))
        }
    }

    @Test
    fun `running a descriptor again stops its running session first, which stays listed as stopped`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            programDeployment(lamp, ENV_1)
            confirmRun(lamp).joinSettled()
            val first = runtime.runs.value.single()
            programStop(lamp, first, ENV_1)
            programDeployment(lamp, ENV_2)

            runtime.rerun(first).also { answerRun() }.joinSettled()

            val (second, previous) = runtime.runs.value
            assertEquals(first.id, previous.id)
            assertEquals(RunPhase.STOPPED, previous.phase)
            assertEquals(SessionState.Closed, previous.session?.state?.value)
            assertEquals(RunPhase.RUNNING, second.phase)
            assertEquals(EnvironmentKey(ENV_2), second.environment)
            assertEquals(1, fakeRuntime.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        }
    }

    @Test
    fun `running a descriptor again while its previous run waits for the runtime cancels that run`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory, scriptSessions = true) {
            open(root)
            val starting = CompletableDeferred<Unit>()
            runtimeHosts.current.held = starting
            val first = confirmRun(lamp)
            assertEquals(RunPhase.WAITING_FOR_RUNTIME, runtime.runs.value.single().phase)

            val second = confirmRun(lamp)
            starting.complete(Unit)
            second.joinSettled()

            val (latest, previous) = runtime.runs.value
            assertTrue(first.isCancelled)
            assertEquals(RunPhase.STOPPED, previous.phase)
            assertNull(previous.session)
            assertEquals(RunPhase.DEPLOYING, latest.phase)
            assertEquals(1, scriptedSessions.launched.size, "only the latest run deploys")
        }
    }

    @Test
    fun `Stop stops the environment of a run`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            programDeployment(lamp, ENV_1)
            confirmRun(lamp).joinSettled()
            val entry = runtime.runs.value.single()
            programStop(lamp, entry, ENV_1)

            runtime.stopSelected().joinSettled()

            assertEquals(RunPhase.STOPPED, runtime.runs.value.single().phase)
            assertIs<SessionState.Stopped>(entry.session?.state?.value)
            assertEquals(1, fakeRuntime.requests(RuntimeRoute.STOP_ENVIRONMENT).size)
        }
    }

    @Test
    fun `a captured run writes the whole log of the runtime into a file of the project until it is stopped`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            val host = runtimeHosts.current
            programDeployment(lamp, ENV_1)
            host.logLines.emit(HostLogLine("before the run"))
            settle()

            val run = runtime.run(lamp)
            val asked = answerRun(capture = true)
            run.joinSettled()
            host.logLines.emit(HostLogLine(TICK_NOISE))
            host.logLines.emit(HostLogLine("Exception in thread main", LogStream.STDERR))
            settle()

            assertEquals(false, asked.capture, "the box starts clear in a new project")
            val entry = runtime.runs.value.single()
            val file = assertNotNull(entry.captureFile)
            assertEquals(root.resolve(WorkspaceOptions.RUN_CAPTURE_DIRECTORY), file.parent)
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.log""").matches(file.fileName.toString()), "$file")
            assertNull(entry.captureProblem)
            val lines = Files.readAllLines(file)
            assertEquals("# Descriptor: ${root.relativize(lamp).joinToString("/")}", lines[1])
            assertTrue("# Environment: $ENV_1" in lines, "$lines")
            assertEquals(listOf("stdout $TICK_NOISE", "stderr Exception in thread main"), lines.takeLast(2))
            assertTrue(lines.none { "before the run" in it }, "the capture starts with the deployment")
            assertTrue(state.settings.value.captureRuns, "the choice is remembered")

            programStop(lamp, entry, ENV_1)
            runtime.stopSelected().joinSettled()
            host.logLines.emit(HostLogLine("after the stop"))
            settle()

            assertTrue(Files.readAllLines(file).none { "after the stop" in it }, "the capture ends with the run")

            workspace.refresh()
            settle()
            assertTrue(workspace.files.value.files().none { it.path.startsWith(file.parent) }, "the captures are not part of the project")

            runtime.run(lamp)
            assertTrue(answer(DialogRequest.ConfigureRun::class, DialogResponse.Cancel).capture, "the box starts ticked the next time")
        }
    }

    @Test
    fun `a run without the capture writes no file`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            programDeployment(lamp, ENV_1)

            confirmRun(lamp).joinSettled()
            runtimeHosts.current.logLines.emit(HostLogLine("a line"))
            settle()

            assertNull(runtime.runs.value.single().captureFile)
            assertFalse(Files.exists(root.resolve(WorkspaceOptions.RUN_CAPTURE_DIRECTORY)))
            assertFalse(state.settings.value.captureRuns)
        }
    }

    @Test
    fun `a capture that cannot be written leaves the run going and says why`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        Files.writeString(root.resolve(WorkspaceOptions.RUN_CAPTURE_DIRECTORY), "a file where the folder belongs")
        withWorkbench(settingsDirectory) {
            open(root)
            programDeployment(lamp, ENV_1)

            runtime.run(lamp).also { answerRun(capture = true) }.joinSettled()

            val entry = runtime.runs.value.single()
            assertEquals(RunPhase.RUNNING, entry.phase)
            assertNull(entry.captureFile)
            assertTrue(!entry.captureProblem.isNullOrBlank(), "${entry.captureProblem}")
        }
    }

    @Test
    fun `changing the runtime settings closes the host and creates the new one`() = runTest(timeout = TIMEOUT) {
        copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            val docker = runtimeHosts.current

            runtime.selectHost(RuntimeHostKind.URL).joinSettled()

            assertEquals(1, docker.closes)
            assertEquals(
                listOf("create DOCKER", "create URL", "close DOCKER"),
                runtimeHosts.lifecycle,
                "the new host is current before the old one closes",
            )
            assertEquals(RuntimeHostKind.URL, runtimeHosts.current.settings.kind)
            assertEquals(RuntimeHostKind.URL, state.settings.value.runtime.host)

            runtime.setUrl("http://127.0.0.1:9090").joinSettled()

            assertEquals(3, runtimeHosts.created.size)
            assertEquals("http://127.0.0.1:9090", runtimeHosts.current.settings.url)
            assertEquals(listOf(root, root, root), runtimeHosts.roots)
        }
    }

    @Test
    fun `closing the workspace shuts down its sessions and closes its host`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        val other = Files.createDirectories(dir.resolve("other")).toAbsolutePath().normalize()
        withWorkbench(settingsDirectory) {
            open(root)
            val host = runtimeHosts.current
            programDeployment(lamp, ENV_1)
            confirmRun(lamp).joinSettled()
            val entry = runtime.runs.value.single()
            programStop(lamp, entry, ENV_1)

            open(other)

            assertEquals(1, fakeRuntime.requests(RuntimeRoute.STOP_ENVIRONMENT).size, "the running environment is stopped")
            assertEquals(SessionState.Closed, entry.session?.state?.value)
            assertEquals(1, host.closes)
            assertEquals(other, runtimeHosts.roots.last(), "the new workspace has a host of its own")
            assertTrue(runtime.runs.value.isEmpty())
        }
    }

    @Test
    fun `shutting down closes the sessions still busy after their bound, so the host is closed in time`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            val host = runtimeHosts.current
            programDeployment(lamp, ENV_1)
            confirmRun(lamp).joinSettled()
            val entry = runtime.runs.value.single()
            fakeRuntime.hang(RuntimeRoute.STOP_ENVIRONMENT)

            val shutdown = scope.launch { runtime.shutdown() }
            scheduler.advanceTimeBy(RuntimeState.SESSIONS_BOUND - 1.milliseconds)
            scheduler.runCurrent()

            assertFalse(shutdown.isCompleted, "the session still stops its environment")
            assertEquals(0, host.closes)

            scheduler.advanceTimeBy(1.milliseconds)
            scheduler.runCurrent()

            assertTrue(shutdown.isCompleted)
            assertEquals(SessionState.Closed, entry.session?.state?.value)
            assertEquals(1, host.closes)
        }
    }

    @Test
    fun `the runs list keeps the ten newest runs`() = runTest(timeout = TIMEOUT) {
        val lamp = copy(SynthesizedModel.LAMP)
        withWorkbench(settingsDirectory) {
            open(root)
            runtimeHosts.current.outcome = FAILED_HOST

            repeat(RunList.MAX_RUNS + 1) { confirmRun(lamp).joinSettled() }

            assertEquals((RunList.MAX_RUNS + 1 downTo 2).map { it.toLong() }, runtime.runs.value.map { it.id })

            runtime.clearFinished()

            assertTrue(runtime.runs.value.isEmpty())
        }
    }

    /** Copies [model] into a folder of its own below the workspace root and returns its descriptor. */
    private fun copy(model: SynthesizedModel): Path =
        SynthesizedModels.copyTo(root.resolve(model.folder), model, DescriptorTiming.EVENT_SCENARIOS).toAbsolutePath().normalize()

    /** Runs [descriptor], or the picked descriptor if it is null, and answers the run dialog without bridges or capture. */
    private fun TestWorkbench.confirmRun(descriptor: Path? = null): Job = runtime.run(descriptor).also { answerRun() }

    /** The runtime state of the opened workspace. */
    private val TestWorkbench.runtime: RuntimeState
        get() = assertNotNull(state.runtime.value, "no runtime state")

    /** The runtime's JSON of the roots of the model of [descriptor]. */
    private fun TestWorkbench.rootsOf(descriptor: Path): List<String> =
        ModelWireText(workspace.analysis.value.contexts.single { it.context.descriptor == descriptor }.models.values.single()).rootsJson()

    /**
     * Programs the answers that a deployment of [descriptor] to [environment] needs beyond the fake runtime's defaults.
     * They are the IDE support check, the read-back after the activation, and the snapshot that confirms the start.
     */
    private suspend fun TestWorkbench.programDeployment(descriptor: Path, environment: String) {
        val prepared = assertIs<Preparation.Ready>(realSessions.prepare(workspace, descriptor)).prepared
        val roots = rootsOf(descriptor)
        val started = prepared.layouts.associate { it.attachedTo to it.initialStack }
        val where = mapOf(ENV_KEY to environment)
        fakeRuntime.respond(
            RuntimeRoute.MODEL_SNAPSHOT,
            FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.MISSING_PARAMETER, mapOf("name" to ENV_KEY)),
        )
        fakeRuntime.respond(
            RuntimeRoute.MODEL_SNAPSHOT,
            FakeResponse.json(RuntimeWireWriter.snapshot(environment, RuntimePhase.ACTIVATED, 0, emptyMap(), roots)),
            where,
        )
        fakeRuntime.respond(
            RuntimeRoute.MODEL_SNAPSHOT,
            FakeResponse.json(RuntimeWireWriter.snapshot(environment, RuntimePhase.RUNNING, started.size.toLong(), started, roots)),
            where,
        )
    }

    /**
     * Programs what stopping the environment of [entry] needs. The runtime closes the link and reports the final
     * model.
     */
    private fun TestWorkbench.programStop(descriptor: Path, entry: RunEntry, environment: String) {
        val clientId = assertNotNull(entry.session).clientId.value
        fakeRuntime.after(RuntimeRoute.STOP_ENVIRONMENT) {
            linksOf(clientId)
                .filter { it.isOpen }
                .forEach { it.closeByRuntime(WireNames.CLOSE_NORMAL, WireNames.REASON_ENVIRONMENT_STOPPED) }
        }
        val final = RuntimeWireWriter.snapshot(environment, RuntimePhase.STOPPED, FINAL_REVISION, emptyMap(), rootsOf(descriptor))
        fakeRuntime.respond(RuntimeRoute.MODEL_SNAPSHOT, FakeResponse.json(final), mapOf(ENV_KEY to environment))
    }

    private companion object {
        val TIMEOUT = 60.seconds

        /** The query parameter of an environment's key. */
        const val ENV_KEY = "envKey"

        /** The first and second environment the fake runtime creates. */
        val ENV_1: String = FakeRuntime.environmentKey(1)
        val ENV_2: String = FakeRuntime.environmentKey(2)

        /** A line the engine trace writes on every tick, which the Log pane leaves out. */
        const val TICK_NOISE = "[12:41:30.123] [env-1] [lamp] TICK_START: tick 3"

        /** The revision of the final model after a stop. */
        const val FINAL_REVISION = 3L

        /** The outcome of a host whose runtime cannot start. */
        val FAILED_HOST = HostOutcome.Failed(HostProblem(HostProblemCode.ENGINE_UNAVAILABLE, "No Docker engine answers.", "Start Docker."))
    }
}
