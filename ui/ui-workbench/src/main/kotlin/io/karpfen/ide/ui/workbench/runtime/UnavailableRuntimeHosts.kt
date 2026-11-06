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

import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostProblem
import io.karpfen.ide.runtime.host.HostProblemCode
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHost
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.host.RuntimeHostSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Path

/**
 * The host factory of a composition without runtime hosts: each host stays stopped and answers every start with
 * [HostProblemCode.ENGINE_UNAVAILABLE]. The workbench services use it unless the composition root hands in the hosts.
 */
internal class UnavailableRuntimeHosts : RuntimeHostFactory {
    override fun create(workspaceRoot: Path, settings: RuntimeHostSettings): RuntimeHost = UnavailableHost(settings.kind)

    /** A host that never has a runtime. */
    private class UnavailableHost(override val kind: RuntimeHostKind) : RuntimeHost {
        override val status: StateFlow<HostStatus> = MutableStateFlow<HostStatus>(HostStatus.Stopped).asStateFlow()

        override val logs: Flow<HostLogLine> = flowOf(HostLogLine(PROBLEM.message))

        override suspend fun ensureRunning(): HostOutcome = HostOutcome.Failed(PROBLEM)

        override suspend fun stop() = Unit

        override fun close() = Unit
    }

    private companion object {
        /** Why no host of this factory has a runtime. */
        val PROBLEM = HostProblem(HostProblemCode.ENGINE_UNAVAILABLE, "This composition of the IDE provides no runtime host.")
    }
}
