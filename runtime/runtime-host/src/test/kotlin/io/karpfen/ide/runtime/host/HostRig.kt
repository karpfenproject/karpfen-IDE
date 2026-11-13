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

import io.karpfen.ide.runtime.host.bundled.RuntimeSources
import io.karpfen.ide.runtime.host.bundled.SourcesLookup
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpoint
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.RuntimeClientOptions
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.protocol.fake.FakeRuntime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path

/**
 * What the host tests share: a [FakeDockerEngine], a [FakeRuntime] that answers the health checks, programmed sources
 * and endpoint, and hosts that run on the test's virtual time.
 *
 * @param scope the test's scope, whose scheduler the hosts and the clients run on.
 */
internal class HostRig(scope: TestScope) {
    /** The engine every Docker host of the rig talks to. */
    val engine = FakeDockerEngine()

    /** The runtime every client of the rig talks to. */
    val fake = FakeRuntime(scope.backgroundScope)

    /** The dispatcher of the hosts and the clients, on the test's scheduler. */
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)

    /** What the hosts find as the bundled sources. */
    var sources: SourcesLookup = SourcesLookup.Found(RuntimeSources(CONTENT_HASH, RUNTIME_COMMIT, 3) { CONTEXT.byteInputStream() })

    /** Where the hosts find the Docker engine. */
    var endpoint: DockerEndpoint = DockerEndpoint.Local(ENGINE_URI)

    /** The endpoints the hosts created clients for, in order. */
    val clientEndpoints = ArrayList<RuntimeEndpoint>()

    /** The engine URIs the hosts connected to, in order. */
    val connections = ArrayList<String>()

    /** What the hosts of the rig work with. */
    val options = HostOptions(
        resolveEndpoint = { endpoint },
        connectEngine = { uri -> connections += uri; engine },
        sources = { sources },
        clients = { endpoint ->
            clientEndpoints += endpoint
            RuntimeClient(endpoint, RuntimeClientOptions(dispatcher = dispatcher, timeSource = scope.testScheduler.timeSource), fake.transports())
        },
        dispatcher = dispatcher,
    )

    /** A Docker host of the workspace [workspace]. */
    fun dockerHost(workspace: Path = WORKSPACE): DockerRuntimeHost = DockerRuntimeHost(workspace, options)

    /** Programs the next [times] IDE support checks to succeed, as a runtime with IDE support answers them. */
    fun supportIde(times: Int = 1) {
        repeat(times) {
            fake.respond(
                RuntimeRoute.MODEL_SNAPSHOT,
                FakeResponse.rejected(FakeResponse.BAD_REQUEST, RuntimeText.MISSING_PARAMETER, mapOf("name" to "envKey")),
            )
        }
    }

    companion object {
        /** The SHA-256 of the programmed sources. */
        val CONTENT_HASH = "0123456789abcdef".repeat(4)

        /** The runtime commit of the programmed sources. */
        const val RUNTIME_COMMIT = "e3d25ec3ade934da7ecdd3554cbe195f1170e6c1"

        /** The content of the programmed sources' archive. */
        const val CONTEXT = "the build context"

        /** The endpoint of the Docker engine. */
        const val ENGINE_URI = "unix:///fake/docker.sock"

        /** The workspace of the hosts. */
        val WORKSPACE: Path = Path.of("/work/robots")
    }
}
