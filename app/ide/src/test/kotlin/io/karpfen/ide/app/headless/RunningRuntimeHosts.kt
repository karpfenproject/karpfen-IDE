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

import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHost
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path

/**
 * Runtime hosts for a runtime that is already running at [endpoint], such as the one a live test is given. Every host
 * reports the runtime as running there and answers every start with it. The hosts start, stop and close nothing, since
 * the runtime belongs to whoever started it, and they have no log of it.
 *
 * @param endpoint where the running runtime is reached.
 */
internal class RunningRuntimeHosts(private val endpoint: RuntimeEndpoint) : RuntimeHostFactory {
    override fun create(workspaceRoot: Path): RuntimeHost = RunningHost()

    /** A host of the runtime at [endpoint]. */
    private inner class RunningHost : RuntimeHost {
        override val status: StateFlow<HostStatus> =
            MutableStateFlow<HostStatus>(HostStatus.Running(endpoint, "running at ${endpoint.httpBase}")).asStateFlow()

        override val logs: Flow<HostLogLine> = emptyFlow()

        override suspend fun ensureRunning(): HostOutcome = HostOutcome.Ready(endpoint)

        override suspend fun stop() = Unit

        override fun close() = Unit
    }
}
