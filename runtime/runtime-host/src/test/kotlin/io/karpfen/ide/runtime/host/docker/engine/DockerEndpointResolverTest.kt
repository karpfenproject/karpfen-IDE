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
package io.karpfen.ide.runtime.host.docker.engine

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [DockerEndpointResolver] with a temporary home folder. `DOCKER_HOST` wins, then the endpoint of the current
 * context, then the platform's default socket. Only `unix://` and `npipe://` endpoints are local.
 */
class DockerEndpointResolverTest {

    @TempDir
    lateinit var home: Path

    @Test
    fun `DOCKER_HOST wins over the current context`() {
        context("desktop-linux", "unix:///contexts/desktop.sock")

        assertEquals(DockerEndpoint.Local("unix:///env.sock"), resolver(dockerHost = "unix:///env.sock").resolve())
        assertEquals(DockerEndpoint.NotLocal("tcp://192.168.1.5:2375"), resolver(dockerHost = "tcp://192.168.1.5:2375").resolve())
    }

    @Test
    fun `the endpoint of the current context is found by its name`() {
        context("colima", "unix:///contexts/colima.sock")
        context("desktop-linux", "unix:///contexts/desktop.sock")
        home.resolve(".docker/config.json").writeText("""{"auths":{},"currentContext":"desktop-linux"}""")

        assertEquals(DockerEndpoint.Local("unix:///contexts/desktop.sock"), resolver().resolve())
    }

    @Test
    fun `a remote context is not local`() {
        context("remote", "tcp://build.example.org:2376")
        home.resolve(".docker/config.json").writeText("""{"currentContext":"remote"}""")

        assertEquals(DockerEndpoint.NotLocal("tcp://build.example.org:2376"), resolver().resolve())
        assertEquals(DockerEndpoint.NotLocal("ssh://user@build.example.org"), resolver(dockerHost = "ssh://user@build.example.org").resolve())
    }

    @Test
    fun `without a described context the platform's default is used`() {
        home.resolve(".docker").createDirectories()
        home.resolve(".docker/config.json").writeText("""{"currentContext":"default"}""")

        assertEquals(DockerEndpoint.Local("unix:///var/run/docker.sock"), resolver(system = "Linux").resolve())
        assertEquals(DockerEndpoint.Local("unix:///var/run/docker.sock"), resolver(system = "Mac OS X").resolve())
        assertEquals(DockerEndpoint.Local("npipe:////./pipe/docker_engine"), resolver(system = "Windows 11").resolve())

        val desktopSocket = home.resolve(".docker/run/docker.sock").also { it.parent.createDirectories() }.apply { writeText("") }
        assertEquals(DockerEndpoint.Local("unix://${desktopSocket.toAbsolutePath()}"), resolver(system = "Mac OS X").resolve())
        assertEquals(DockerEndpoint.Local("unix:///var/run/docker.sock"), resolver(system = "Linux").resolve())
    }

    @Test
    fun `an unreadable configuration falls back to the platform's default`() {
        home.resolve(".docker").createDirectories()
        home.resolve(".docker/config.json").writeText("not json")

        assertEquals(DockerEndpoint.Local("unix:///var/run/docker.sock"), resolver(system = "Linux").resolve())
    }

    /** A resolver that sees [dockerHost] as `DOCKER_HOST`, the temporary home folder and the operating system [system]. */
    private fun resolver(dockerHost: String? = null, system: String = "Linux"): DockerEndpointResolver =
        DockerEndpointResolver(
            environment = { name -> dockerHost.takeIf { name == "DOCKER_HOST" } },
            home = home,
            operatingSystem = system,
        )

    /** Describes the Docker context [name] with the engine endpoint [host], as `docker context create` does. */
    private fun context(name: String, host: String) {
        val folder = home.resolve(".docker/contexts/meta/${name.hashCode().toUInt().toString(16)}").createDirectories()
        folder.resolve("meta.json").writeText(
            """{"Name":"$name","Metadata":{"Description":""},"Endpoints":{"docker":{"Host":"$host","SkipTLSVerify":false}}}""",
        )
    }
}
