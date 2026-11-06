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

import io.karpfen.ide.runtime.host.bundled.BundledRuntimeSources
import io.karpfen.ide.runtime.host.bundled.SourcesLookup
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpoint
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpointResolver
import io.karpfen.ide.runtime.host.docker.engine.DockerEngine
import io.karpfen.ide.runtime.host.docker.engine.DockerJavaEngine
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The factory of the IDE's two hosts: the Docker container the IDE builds and runs for each workspace, and a runtime
 * attached by URL.
 */
public fun RuntimeHosts(): RuntimeHostFactory = DefaultRuntimeHosts(HostOptions())

/**
 * Creates a [DockerRuntimeHost] or a [UrlRuntimeHost] as the settings choose, both working with [options]; tests hand in
 * a fake engine, the fake runtime's clients, their own sources and a test dispatcher.
 *
 * @param options what the hosts work with.
 */
internal class DefaultRuntimeHosts(private val options: HostOptions) : RuntimeHostFactory {
    override fun create(workspaceRoot: Path, settings: RuntimeHostSettings): RuntimeHost =
        when (settings.kind) {
            RuntimeHostKind.DOCKER -> DockerRuntimeHost(workspaceRoot, options)
            RuntimeHostKind.URL -> UrlRuntimeHost(settings.url, options)
        }
}

/**
 * What the hosts work with, by default the real Docker engine, the runtime sources bundled with the IDE and the real
 * runtime client.
 *
 * @property resolveEndpoint finds the Docker engine.
 * @property connectEngine connects to the Docker engine at an endpoint URI.
 * @property sources reads the runtime sources the image is built from.
 * @property clients creates a client of the runtime at an endpoint, for the health checks; the host closes it.
 * @property dispatcher where the hosts' own coroutines run, the container watcher and the log follower, and so their
 *   clock: a test dispatcher runs them on virtual time.
 * @property timings the intervals and bounds of the hosts.
 */
internal data class HostOptions(
    val resolveEndpoint: () -> DockerEndpoint = { DockerEndpointResolver().resolve() },
    val connectEngine: (String) -> DockerEngine = ::DockerJavaEngine,
    val sources: () -> SourcesLookup = { BundledRuntimeSources().read() },
    val clients: (RuntimeEndpoint) -> RuntimeClient = { endpoint -> RuntimeClient(endpoint) },
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val timings: HostTimings = HostTimings(),
)

/**
 * The intervals and bounds of the hosts.
 *
 * @property healthPoll how often the Docker host asks a starting runtime for its health.
 * @property healthTimeout how long a starting runtime may take to answer; the JVM and the first Python start are slow.
 * @property watchInterval how often the Docker host checks that its container still runs.
 * @property stopTimeout how long the engine waits for the runtime to stop before it kills the container.
 * @property closeBound how long closing a host may take to stop its runtime.
 */
internal data class HostTimings(
    val healthPoll: Duration = 500.milliseconds,
    val healthTimeout: Duration = 120.seconds,
    val watchInterval: Duration = 5.seconds,
    val stopTimeout: Duration = 10.seconds,
    val closeBound: Duration = 15.seconds,
)
