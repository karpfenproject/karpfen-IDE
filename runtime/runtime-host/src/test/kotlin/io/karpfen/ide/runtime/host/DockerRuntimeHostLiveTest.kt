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

import io.karpfen.ide.runtime.host.docker.WorkspaceContainer
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpoint
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpointResolver
import io.karpfen.ide.runtime.host.docker.engine.DockerJavaEngine
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [DockerRuntimeHost] end to end against the local Docker engine with the runtime sources bundled with the IDE. It
 * builds the runtime image unless it exists, which takes minutes the first time. It starts the container, waits for the
 * runtime, adopts the container from a second host of the same workspace and stops it, and then the container must be
 * gone. The workspace lies in the test's temporary folder, so the test never adopts or removes a container of a real
 * workspace. It removes its containers in `finally`. The image stays, because it is the IDE's own image. The test runs
 * only in the opt-in task `liveDockerTest`.
 */
@Tag("live-docker")
class DockerRuntimeHostLiveTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `the host builds or reuses the image, starts, adopts and stops the runtime container`() = runBlocking {
        val workspaceRoot = folder.resolve("workspace")
        val workspace = WorkspaceContainer(workspaceRoot)
        val engine = DockerJavaEngine((DockerEndpointResolver().resolve() as DockerEndpoint.Local).uri)
        val first = RuntimeHosts().create(workspaceRoot)
        val second = RuntimeHosts().create(workspaceRoot)
        try {
            val started = assertIs<HostOutcome.Ready>(first.ensureRunning(), "${first.status.value}")
            assertIs<HostStatus.Running>(first.status.value)
            RuntimeClient(started.endpoint).use { client -> assertEquals(RuntimeResult.Ok(Unit), client.health()) }
            val startupLine = withTimeoutOrNull(LOG_WAIT) { first.logs.first { STARTUP_LINE in it.text } }
            assertNotNull(startupLine, "the runtime's log has no line with '$STARTUP_LINE'")
            val containers = engine.listContainers(workspace.filter, includeStopped = true)
            assertEquals(1, containers.size, "$containers")

            val adopted = assertIs<HostOutcome.Ready>(second.ensureRunning(), "${second.status.value}")
            assertEquals(started.endpoint, adopted.endpoint)
            assertEquals(containers.map { it.id }, engine.listContainers(workspace.filter, includeStopped = true).map { it.id })

            second.stop()
            assertEquals(HostStatus.Stopped, second.status.value)
            assertEquals(emptyList(), engine.listContainers(workspace.filter, includeStopped = true))
            assertTrue(second.logs.first { "Removed the runtime container" in it.text }.text.isNotEmpty())
        } finally {
            second.close()
            first.close()
            engine.listContainers(workspace.filter, includeStopped = true).forEach { engine.removeContainer(it.id) }
            engine.close()
        }
    }

    private companion object {
        /** The line the runtime logs once its server listens. */
        const val STARTUP_LINE = "[KtorServer] Started on"

        /** How long the test waits for the startup line to reach the host's log. */
        val LOG_WAIT = 30.seconds
    }
}
