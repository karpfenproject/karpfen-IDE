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
package io.karpfen.ide.app.launch

import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostProblem
import io.karpfen.ide.runtime.host.HostProblemCode
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHost
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.host.RuntimeHostSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [runtimeHostsClosedAtExit] with a registration the test keeps instead of a shutdown hook. The test checks that
 * exactly one thread is registered and that running it closes a host that is still open.
 */
class RuntimeHostsAtExitTest {

    @Test
    fun `the registered thread closes the hosts that are still open`() {
        val registered = ArrayList<Thread>()
        val created = ArrayList<ClosableHost>()
        val hosts = runtimeHostsClosedAtExit(hosts = { _, _ -> ClosableHost().also { created += it } }, atExit = { registered += it })
        hosts.create(Path.of("workspace"), RuntimeHostSettings(RuntimeHostKind.DOCKER))
        assertEquals(listOf(false), created.map { it.closed })

        registered.single().run()

        assertEquals(listOf(true), created.map { it.closed })
    }

    /** A host that only knows whether it was closed. */
    private class ClosableHost : RuntimeHost {
        /** True once [close] was called. */
        var closed = false

        override val kind: RuntimeHostKind = RuntimeHostKind.DOCKER
        override val status: StateFlow<HostStatus> = MutableStateFlow(HostStatus.Stopped)
        override val logs: Flow<HostLogLine> = emptyFlow()

        override suspend fun ensureRunning(): HostOutcome =
            HostOutcome.Failed(HostProblem(HostProblemCode.CONTAINER_FAILED, "This host runs nothing."))

        override suspend fun stop() = Unit

        override fun close() {
            closed = true
        }
    }
}
