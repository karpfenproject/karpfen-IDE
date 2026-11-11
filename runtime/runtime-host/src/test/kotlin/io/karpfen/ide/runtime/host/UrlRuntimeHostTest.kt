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

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.transport.TransportFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers [UrlRuntimeHost] against the fake runtime with programmed answers. One health check and one IDE support check
 * make it run. Each failure has its problem code, its log is one line, and stopping only forgets the runtime.
 */
class UrlRuntimeHostTest {

    @Test
    fun `a runtime that answers and supports the IDE runs at its URL`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        val host = rig.urlHost("http://127.0.0.1:8080")

        val endpoint = RuntimeEndpoint(secure = false, host = "127.0.0.1", port = 8080)
        assertEquals(HostOutcome.Ready(endpoint), host.ensureRunning())
        assertEquals(HostStatus.Running(endpoint, "URL · http://127.0.0.1:8080"), host.status.value)
        assertEquals(listOf(RuntimeRoute.HEALTH, RuntimeRoute.MODEL_SNAPSHOT), rig.fake.requests.map { it.route })
    }

    @Test
    fun `a URL without a port means the scheme's default port`() = runTest {
        val rig = HostRig(this).apply { supportIde(times = 2) }

        assertEquals(
            HostOutcome.Ready(RuntimeEndpoint(secure = false, host = "runtime.example.org", port = 80)),
            rig.urlHost("http://runtime.example.org").ensureRunning(),
        )
        assertEquals(
            HostOutcome.Ready(RuntimeEndpoint(secure = true, host = "runtime.example.org", port = 443)),
            rig.urlHost("https://runtime.example.org/").ensureRunning(),
        )
    }

    @Test
    fun `each failure is reported with its problem code`() = runTest {
        val rig = HostRig(this)

        assertProblem(HostProblemCode.URL_INVALID, rig.urlHost("127.0.0.1:8080"))
        assertProblem(HostProblemCode.URL_INVALID, rig.urlHost("ftp://127.0.0.1:8080"))
        assertEquals(emptyList(), rig.fake.requests)

        rig.fake.fail(RuntimeRoute.HEALTH, TransportFailure.Unreachable("connection refused"))
        assertProblem(HostProblemCode.URL_UNREACHABLE, rig.urlHost("http://127.0.0.1:8080"))

        val unsupported = assertProblem(HostProblemCode.NO_IDE_SUPPORT, rig.urlHost("http://127.0.0.1:8080"))
        assertTrue("IDE support" in unsupported.hint.orEmpty(), unsupported.hint)
    }

    @Test
    fun `the log says in one line that the runtime's log is elsewhere`() = runTest {
        val host = HostRig(this).urlHost("http://127.0.0.1:8080")

        val line = host.logs.first()

        assertTrue("runs elsewhere" in line.text && "http://127.0.0.1:8080" in line.text, line.text)
    }

    @Test
    fun `stopping only forgets the runtime`() = runTest {
        val rig = HostRig(this).apply { supportIde() }
        val host = rig.urlHost("http://127.0.0.1:8080")
        host.ensureRunning()
        val requests = rig.fake.requests

        host.stop()

        assertEquals(HostStatus.Stopped, host.status.value)
        assertEquals(requests, rig.fake.requests)
    }

    @Test
    fun `the default factory creates the host the settings choose`() {
        val hosts = RuntimeHosts()

        hosts.create(Path.of("workspace"), RuntimeHostSettings(RuntimeHostKind.DOCKER)).use { assertIs<DockerRuntimeHost>(it) }
        hosts.create(Path.of("workspace"), RuntimeHostSettings(RuntimeHostKind.URL, "http://127.0.0.1:9090")).use { assertIs<UrlRuntimeHost>(it) }
    }

    /** Asserts that [host] fails with [code] in its outcome and its status, and answers the problem. */
    private suspend fun assertProblem(code: HostProblemCode, host: UrlRuntimeHost): HostProblem {
        val outcome = assertIs<HostOutcome.Failed>(host.ensureRunning(), "expected $code")
        assertEquals(code, outcome.problem.code, outcome.problem.message)
        assertEquals(HostStatus.Failed(outcome.problem), host.status.value)
        return outcome.problem
    }
}
