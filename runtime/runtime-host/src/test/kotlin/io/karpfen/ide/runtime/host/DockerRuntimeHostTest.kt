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
package io.karpfen.ide.runtime.host

import io.karpfen.ide.runtime.host.bundled.SourcesLookup
import io.karpfen.ide.runtime.host.docker.RuntimeLabels
import io.karpfen.ide.runtime.host.docker.WorkspaceContainer
import io.karpfen.ide.runtime.host.docker.engine.ContainerInspection
import io.karpfen.ide.runtime.host.docker.engine.ContainerOutputLine
import io.karpfen.ide.runtime.host.docker.engine.ContainerSpec
import io.karpfen.ide.runtime.host.docker.engine.ContainerSummary
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpoint
import io.karpfen.ide.runtime.host.docker.engine.ImageSummary
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers [DockerRuntimeHost] against a [FakeDockerEngine] that answers what each test programs. The fake runtime answers
 * the health checks, and everything runs on virtual time. The tests cover adoption and the removal of stale containers,
 * the image build and the removal of older images, and the container's configuration and port. They check each failure
 * with its problem code. They check that calls join a start under way and get its answer, a failure or a stop
 * included, that a failed start removes its container and that a closed host starts nothing. They also check that stop
 * removes only the host's own container, and that the watcher notices a container that died but tolerates a passing
 * failure of the engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DockerRuntimeHostTest {

    /** The container names and labels of the rig's workspace. */
    private val workspace = WorkspaceContainer(HostRig.WORKSPACE)

    /** The endpoint of the default container. */
    private val endpoint = RuntimeEndpoint(secure = false, host = "127.0.0.1", port = FakeDockerEngine.HOST_PORT)

    @Test
    fun `a running container of this workspace built from the current sources is adopted`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        rig.engine.containers = { filter -> if (filter == workspace.filter) listOf(container("c-running", running = true)) else emptyList() }
        rig.engine.inspection = ContainerInspection(running = true, exitCode = null, hostPort = 50_000)

        rig.dockerHost().use { host ->
            val outcome = host.ensureRunning()

            val adopted = RuntimeEndpoint(secure = false, host = "127.0.0.1", port = 50_000)
            assertEquals(HostOutcome.Ready(adopted), outcome)
            assertEquals(listOf(adopted), rig.clientEndpoints)
            assertEquals(
                listOf(
                    EngineCall.Ping,
                    EngineCall.ListContainers(workspace.filter, includeStopped = true),
                    EngineCall.InspectContainer("c-running"),
                    EngineCall.FollowLogs("c-running"),
                ),
                rig.engine.calls,
            )
        }
    }

    @Test
    fun `stopped containers and those of other sources are removed before a new one is created`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        rig.engine.containers = { filter ->
            if (filter == workspace.filter) {
                listOf(container("c-stopped", running = false), container("c-other", running = true, hash = "f".repeat(64)))
            } else {
                emptyList()
            }
        }

        rig.dockerHost().use { host ->
            assertEquals(HostOutcome.Ready(endpoint), host.ensureRunning())
        }

        val calls = rig.engine.calls
        assertEquals(
            listOf(
                EngineCall.RemoveContainer("c-stopped"),
                EngineCall.StopContainer("c-other", 10.seconds),
                EngineCall.RemoveContainer("c-other"),
            ),
            calls.filter { it is EngineCall.RemoveContainer || it is EngineCall.StopContainer }.take(3),
        )
        assertTrue(
            calls.indexOf(EngineCall.RemoveContainer("c-other")) < calls.indexOfFirst { it is EngineCall.CreateContainer },
            "$calls",
        )
    }

    @Test
    fun `the image is built only when its tag is absent, and the status shows the build's step`() = runTest {
        val rig = HostRig(this).apply { supportIde(times = 2) }
        val image = "karpfen-ide/runtime:${HostRig.CONTENT_HASH.take(12)}"

        rig.dockerHost().use { host ->
            host.ensureRunning()
        }
        assertEquals(listOf(EngineCall.ImageExists(image)), rig.engine.callsOf<EngineCall.ImageExists>())
        assertEquals(emptyList(), rig.engine.callsOf<EngineCall.BuildImage>())

        rig.engine.imageExists = false
        rig.engine.buildOutput = listOf("Step 1/14 : FROM eclipse-temurin:21-jdk AS builder", " ---> 1a2b3c", "Step 2/14 : WORKDIR /build")
        val statuses = ArrayList<HostStatus>()
        rig.dockerHost().use { host ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.status.toList(statuses) }
            host.ensureRunning()
        }

        val build = rig.engine.callsOf<EngineCall.BuildImage>().single()
        assertEquals(EngineCall.BuildImage(HostRig.CONTEXT, image, imageLabels()), build)
        assertTrue(HostStatus.Preparing(HostStage.BUILDING_IMAGE, "Step 1/14") in statuses, "$statuses")
        assertTrue(HostStatus.Preparing(HostStage.BUILDING_IMAGE, "Step 2/14") in statuses, "$statuses")
        assertTrue(HostStatus.Preparing(HostStage.BUILDING_IMAGE, " ---> 1a2b3c") !in statuses, "$statuses")
    }

    @Test
    fun `after a build, older images of the runtime are removed unless a container uses them`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        rig.engine.imageExists = false
        rig.engine.images = listOf(
            ImageSummary("sha256:current", imageLabels()),
            ImageSummary("sha256:old-used", imageLabels(hash = "a".repeat(64))),
            ImageSummary("sha256:old", imageLabels(hash = "b".repeat(64))),
        )
        rig.engine.containers = { filter ->
            if (filter == RuntimeLabels.OURS) listOf(container("c-elsewhere", running = false, imageId = "sha256:old-used")) else emptyList()
        }

        rig.dockerHost().use { it.ensureRunning() }

        assertEquals(listOf(EngineCall.RemoveImage("sha256:old")), rig.engine.callsOf<EngineCall.RemoveImage>())
        assertEquals(listOf(EngineCall.ListImages(mapOf(RuntimeLabels.RUNTIME to "true"))), rig.engine.callsOf<EngineCall.ListImages>())
    }

    @Test
    fun `the container gets its name, labels, port and configuration file`() = runTest {
        val rig = HostRig(this).apply { supportIde() }

        rig.dockerHost().use { it.ensureRunning() }

        val spec = rig.engine.callsOf<EngineCall.CreateContainer>().single().spec
        assertEquals(
            ContainerSpec(
                image = "karpfen-ide/runtime:${HostRig.CONTENT_HASH.take(12)}",
                name = workspace.name,
                labels = mapOf(
                    "io.karpfen.ide.runtime" to "true",
                    "io.karpfen.ide.workspace" to HostRig.WORKSPACE.toAbsolutePath().normalize().toString(),
                    "io.karpfen.ide.workspace.hash" to workspace.hash,
                    "io.karpfen.ide.runtime.hash" to HostRig.CONTENT_HASH,
                ),
                hostIp = "127.0.0.1",
                containerPort = 8080,
                autoRemove = false,
                stopTimeout = 10.seconds,
            ),
            spec,
        )
        assertTrue(Regex("karpfen-ide-runtime-[0-9a-f]{10}").matches(workspace.name), workspace.name)
        val copy = rig.engine.callsOf<EngineCall.CopyToContainer>().single()
        assertEquals(FakeDockerEngine.CREATED_ID to "/app", copy.id to copy.path)
        val file = TarEntries.read(copy.tarBytes).single()
        assertEquals("application.conf" to "644", file.path to file.mode)
        assertEquals(CONFIGURATION, file.text)
        val calls = rig.engine.calls
        assertTrue(calls.indexOf(copy) < calls.indexOf(EngineCall.StartContainer(FakeDockerEngine.CREATED_ID)), "$calls")
    }

    @Test
    fun `the published port is where the runtime is reached and what the status describes`() = runTest {
        val rig = HostRig(this).apply { supportIde() }

        rig.dockerHost().use { host ->
            assertEquals(HostOutcome.Ready(endpoint), host.ensureRunning())
            assertEquals(
                HostStatus.Running(endpoint, "Docker, image ${HostRig.CONTENT_HASH.take(12)}, 127.0.0.1:${FakeDockerEngine.HOST_PORT}"),
                host.status.value,
            )
        }
        assertEquals(listOf(endpoint), rig.clientEndpoints)
        assertEquals(listOf(RuntimeRoute.HEALTH, RuntimeRoute.MODEL_SNAPSHOT), rig.fake.requests.map { it.route })
    }

    @Test
    fun `each failure is reported with its problem code`() = runTest {
        assertFailure(HostProblemCode.ENGINE_NOT_LOCAL) { endpoint = DockerEndpoint.NotLocal("tcp://build.example.org:2376") }
        assertFailure(HostProblemCode.ENGINE_UNAVAILABLE) { engine.failures["ping"] = "connection refused" }
        assertFailure(HostProblemCode.SOURCES_MISSING) { sources = SourcesLookup.Missing("the archive is missing") }
        assertFailure(HostProblemCode.CONTAINER_FAILED) { engine.failures["createContainer"] = "the name is in use" }
        assertFailure(HostProblemCode.CONTAINER_FAILED) { engine.failures["startContainer"] = "port is already allocated" }
        assertFailure(HostProblemCode.CONTAINER_FAILED) { engine.inspection = ContainerInspection(running = true, exitCode = null, hostPort = null) }
        assertFailure(HostProblemCode.NO_IDE_SUPPORT) { }

        val build = assertFailure(HostProblemCode.IMAGE_BUILD_FAILED) {
            engine.imageExists = false
            engine.buildOutput = listOf("Step 3/14 : RUN ./gradlew installDist")
            engine.failures["buildImage"] = "The command '/bin/sh -c ./gradlew installDist' returned a non-zero code: 1"
        }
        assertTrue("returned a non-zero code: 1" in build.message, build.message)

        val unhealthy = assertFailure(HostProblemCode.UNHEALTHY) {
            engine.logLines = listOf(ContainerOutputLine("Exception in thread \"main\" java.net.BindException", fromStderr = true))
            repeat(300) { fake.fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("connection refused")) }
        }
        assertTrue("java.net.BindException" in unhealthy.message, unhealthy.message)
    }

    @Test
    fun `a call during a start joins it instead of starting a second container`() = runTest {
        val rig = HostRig(this).apply { supportIde() }

        rig.dockerHost().use { host ->
            val first = async { host.ensureRunning() }
            val second = async { host.ensureRunning() }

            assertEquals(HostOutcome.Ready(endpoint), first.await())
            assertEquals(HostOutcome.Ready(endpoint), second.await())
        }
        assertEquals(1, rig.engine.callsOf<EngineCall.CreateContainer>().size)
        assertEquals(1, rig.engine.callsOf<EngineCall.StartContainer>().size)
    }

    @Test
    fun `a start that fails answers every call waiting for it at once`() = runTest {
        val rig = HostRig(this)
        rig.engine.imageExists = false
        rig.engine.failures["buildImage"] = "The command '/bin/sh -c ./gradlew installDist' returned a non-zero code: 1"

        rig.dockerHost().use { host ->
            val first = async { host.ensureRunning() }
            val second = async { host.ensureRunning() }

            val failed = assertIs<HostOutcome.Failed>(first.await())
            assertEquals(HostProblemCode.IMAGE_BUILD_FAILED, failed.problem.code)
            assertEquals(failed, second.await())
        }
        assertEquals(1, rig.engine.callsOf<EngineCall.BuildImage>().size)
    }

    @Test
    fun `stop cancels a start under way, answers every call waiting for it and starts nothing again`() = runTest {
        val rig = HostRig(this)
        repeat(300) { rig.fake.fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("connection refused")) }

        rig.dockerHost().use { host ->
            val first = async { host.ensureRunning() }
            val second = async { host.ensureRunning() }
            advanceTimeBy(1.seconds)
            assertEquals(HostStatus.Preparing(HostStage.WAITING_FOR_HEALTH), host.status.value)

            host.stop()

            val stopped = assertIs<HostOutcome.Failed>(first.await())
            assertEquals(HostProblemCode.CONTAINER_FAILED, stopped.problem.code)
            assertEquals(stopped, second.await())
            assertEquals(HostStatus.Stopped, host.status.value)
        }
        assertEquals(1, rig.engine.callsOf<EngineCall.Ping>().size)
        assertEquals(1, rig.engine.callsOf<EngineCall.CreateContainer>().size)
        assertEquals(listOf(EngineCall.RemoveContainer(FakeDockerEngine.CREATED_ID)), rig.engine.callsOf<EngineCall.RemoveContainer>())
    }

    @Test
    fun `a start that fails after creating the container removes it, and the next start creates a new one`() = runTest {
        val rig = HostRig(this)
        repeat(250) { rig.fake.fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("connection refused")) }

        rig.dockerHost().use { host ->
            assertEquals(HostProblemCode.UNHEALTHY, assertIs<HostOutcome.Failed>(host.ensureRunning()).problem.code)
            val created = FakeDockerEngine.CREATED_ID
            assertEquals(
                listOf(EngineCall.StopContainer(created, 10.seconds), EngineCall.RemoveContainer(created)),
                rig.engine.calls.filter { it is EngineCall.StopContainer || it is EngineCall.RemoveContainer },
            )

            rig.supportIde()
            assertEquals(HostOutcome.Ready(endpoint), host.ensureRunning())
        }
        assertEquals(2, rig.engine.callsOf<EngineCall.CreateContainer>().size)
    }

    @Test
    fun `after close the host starts nothing`() = runTest {
        val rig = HostRig(this)
        val host = rig.dockerHost()
        host.close()

        val outcome = assertIs<HostOutcome.Failed>(host.ensureRunning())

        assertEquals(HostProblemCode.CONTAINER_FAILED, outcome.problem.code)
        assertEquals(emptyList(), rig.engine.calls)
    }

    @Test
    fun `stop removes only the host's own container and is idempotent`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        rig.engine.createdId = "c-own"
        val host = rig.dockerHost()
        host.ensureRunning()
        val before = rig.engine.calls.size

        host.stop()
        host.stop()

        assertEquals(
            listOf(EngineCall.StopContainer("c-own", 10.seconds), EngineCall.RemoveContainer("c-own")),
            rig.engine.calls.drop(before),
        )
        assertEquals(HostStatus.Stopped, host.status.value)
        host.close()
    }

    @Test
    fun `the watcher reports a container that died, and the next call starts a new one`() = runTest {
        val rig = HostRig(this).apply { supportIde(times = 2) }

        rig.dockerHost().use { host ->
            host.ensureRunning()
            rig.engine.inspection = ContainerInspection(running = false, exitCode = 137, hostPort = null)
            advanceTimeBy(5.seconds)
            runCurrent()

            val failed = assertIs<HostStatus.Failed>(host.status.value)
            assertEquals(HostProblemCode.CONTAINER_DIED, failed.problem.code)
            assertTrue("137" in failed.problem.message, failed.problem.message)

            rig.engine.inspection = ContainerInspection(running = true, exitCode = null, hostPort = FakeDockerEngine.HOST_PORT)
            assertEquals(HostOutcome.Ready(endpoint), host.ensureRunning())
        }
        assertEquals(2, rig.engine.callsOf<EngineCall.CreateContainer>().size)
    }

    @Test
    fun `the watcher tries a failed inspection again and gives the container up after three in a row`() = runTest {
        val rig = HostRig(this).apply { supportIde() }

        rig.dockerHost().use { host ->
            host.ensureRunning()
            val running = host.status.value
            rig.engine.failures["inspectContainer"] = "Read timed out"
            advanceTimeBy(5.seconds)
            runCurrent()
            rig.engine.failures.clear()
            advanceTimeBy(5.seconds)
            runCurrent()
            rig.engine.failures["inspectContainer"] = "Read timed out"
            advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals(running, host.status.value)

            advanceTimeBy(5.seconds)
            runCurrent()

            val failed = assertIs<HostStatus.Failed>(host.status.value)
            assertEquals(HostProblemCode.CONTAINER_DIED, failed.problem.code)
            assertTrue("Read timed out" in failed.problem.message, failed.problem.message)
        }
    }

    @Test
    fun `the log replays the host's lines and the container's lines with their streams`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        rig.engine.logLines = listOf(
            ContainerOutputLine("[KtorServer] Started on http://0.0.0.0:8080", fromStderr = false),
            ContainerOutputLine("SLF4J: No providers were found.", fromStderr = true),
        )

        rig.dockerHost().use { host ->
            host.ensureRunning()
            val lines = ArrayList<HostLogLine>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.logs.toList(lines) }
            collector.cancel()

            assertTrue(HostLogLine("Checking the Docker engine.") in lines, "$lines")
            assertTrue(HostLogLine("[KtorServer] Started on http://0.0.0.0:8080", LogStream.STDOUT) in lines, "$lines")
            assertTrue(HostLogLine("SLF4J: No providers were found.", LogStream.STDERR) in lines, "$lines")
        }
    }

    /**
     * Starts a host of a rig that [program] sets up, asserts that it fails with [code] in its outcome and its status,
     * and answers the problem.
     */
    private suspend fun TestScope.assertFailure(code: HostProblemCode, program: HostRig.() -> Unit): HostProblem {
        val rig = HostRig(this).apply { if (code != HostProblemCode.NO_IDE_SUPPORT) supportIde() }
        rig.program()
        rig.dockerHost().use { host ->
            val outcome = assertIs<HostOutcome.Failed>(host.ensureRunning(), "expected $code")
            assertEquals(code, outcome.problem.code, outcome.problem.message)
            assertEquals(HostStatus.Failed(outcome.problem), host.status.value)
            return outcome.problem
        }
    }

    /** A container of the rig's workspace, built from [hash]. */
    private fun container(id: String, running: Boolean, hash: String = HostRig.CONTENT_HASH, imageId: String = "sha256:image"): ContainerSummary =
        ContainerSummary(
            id = id,
            imageId = imageId,
            running = running,
            labels = mapOf(
                RuntimeLabels.RUNTIME to "true",
                RuntimeLabels.WORKSPACE_HASH to workspace.hash,
                RuntimeLabels.RUNTIME_HASH to hash,
            ),
        )

    /** The labels of an image of the runtime built from [hash]. */
    private fun imageLabels(hash: String = HostRig.CONTENT_HASH): Map<String, String> =
        mapOf(
            "io.karpfen.ide.runtime" to "true",
            "io.karpfen.ide.runtime.hash" to hash,
            "io.karpfen.ide.runtime.commit" to HostRig.RUNTIME_COMMIT,
        )

    private companion object {
        /** The configuration of the runtime in the container. */
        val CONFIGURATION = """
            server {
              host = "0.0.0.0"
              port = 8080
            }
            websocket {
              queueTimeoutMs = 1000
              enabled = true
            }
            logging {
              level = "INFO"
              consoleOutput = true
            }
            engine {
              defaultTickDelayMs = 50
              defaultEventTtlMs = 3000
              eventConsumptionOnFire = true
            }
            engineTracing {
              tracingEnabled = false
              tracingLogDirectory = "/app/logs"
              tracingConsoleOutput = false
              simpleTrace = true
            }

        """.trimIndent()
    }
}
