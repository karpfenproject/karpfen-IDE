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

import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A runtime started elsewhere and reached at [url]. [ensureRunning] asks it once for its health and its IDE support;
 * [stop] only forgets it, since the IDE does not own it. Its log is shown where it was started, so [logs] says so in one
 * line.
 *
 * @param url the base URL of the runtime, such as `http://127.0.0.1:8080`.
 * @param options what the host works with; it uses their clients.
 */
internal class UrlRuntimeHost(private val url: String, private val options: HostOptions) : RuntimeHost {

    override val kind: RuntimeHostKind = RuntimeHostKind.URL

    /** The status as the host sets it. */
    private val state = MutableStateFlow<HostStatus>(HostStatus.Stopped)

    override val status: StateFlow<HostStatus> = state.asStateFlow()

    /** The one line of the log. */
    private val log = HostLog().apply { host("The runtime at $url runs elsewhere, so its log is shown where it was started.") }

    override val logs: Flow<HostLogLine> = log.lines

    /** Serializes the checks. */
    private val mutex = Mutex()

    override suspend fun ensureRunning(): HostOutcome =
        mutex.withLock {
            val endpoint = try {
                RuntimeEndpoint.parse(url.trim())
            } catch (invalid: IllegalArgumentException) {
                return@withLock fail(
                    HostProblem(HostProblemCode.URL_INVALID, "The runtime URL is invalid: ${invalid.message}", "Enter a URL such as $EXAMPLE."),
                )
            }
            state.value = HostStatus.Preparing(HostStage.WAITING_FOR_HEALTH)
            val problem = options.clients(endpoint).use { client ->
                when (val health = client.health()) {
                    is RuntimeResult.Failed -> HostProblem(
                        HostProblemCode.URL_UNREACHABLE,
                        "No runtime answers at ${endpoint.httpBase}: ${health.failure.describe()}",
                        "Start the runtime there, or check the URL.",
                    )
                    is RuntimeResult.Ok -> (client.checkIdeSupport() as? RuntimeResult.Failed)?.let { support ->
                        HostProblem(
                            HostProblemCode.NO_IDE_SUPPORT,
                            "The runtime at ${endpoint.httpBase} lacks IDE support: ${support.failure.describe()}",
                            "The runtime needs the branch with IDE support.",
                        )
                    }
                }
            }
            if (problem != null) return@withLock fail(problem)
            state.value = HostStatus.Running(endpoint, "URL · ${endpoint.httpBase}")
            HostOutcome.Ready(endpoint)
        }

    override suspend fun stop() {
        state.value = HostStatus.Stopped
    }

    override fun close() {
        state.value = HostStatus.Stopped
    }

    /** Sets the status to [problem] and answers it. */
    private fun fail(problem: HostProblem): HostOutcome {
        state.value = HostStatus.Failed(problem)
        return HostOutcome.Failed(problem)
    }

    private companion object {
        /** A URL of a runtime started with its own defaults. */
        const val EXAMPLE = RuntimeHostSettings.DEFAULT_URL
    }
}
