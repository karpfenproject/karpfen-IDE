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

import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.WireValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** Where the Docker engine is reached, as [DockerEndpointResolver] found it. */
internal sealed interface DockerEndpoint {
    /** The endpoint as a URI, such as `unix:///var/run/docker.sock`. */
    val uri: String

    /** A socket or named pipe of this machine, which the host may use. */
    data class Local(override val uri: String) : DockerEndpoint

    /** An endpoint elsewhere, such as `tcp://`, which would publish the runtime on another machine. */
    data class NotLocal(override val uri: String) : DockerEndpoint
}

/**
 * Finds the Docker engine as the Docker command line does: `DOCKER_HOST` if it is set; else the endpoint of the current
 * Docker context, named by `currentContext` in `~/.docker/config.json` and described by the context's
 * `~/.docker/contexts/meta/<digest>/meta.json` whose `Name` matches (`Endpoints.docker.Host`); else the platform's
 * default socket: `~/.docker/run/docker.sock` on macOS when it exists, Docker Desktop's socket, the named pipe
 * `npipe:////./pipe/docker_engine` on Windows, and `unix:///var/run/docker.sock` otherwise. Only `unix://` and
 * `npipe://` endpoints are local.
 *
 * @param environment reads an environment variable.
 * @param home the user's home folder.
 * @param operatingSystem the name of the operating system, as the system property `os.name` gives it.
 */
internal class DockerEndpointResolver(
    private val environment: (String) -> String? = System::getenv,
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val operatingSystem: String = System.getProperty("os.name").orEmpty(),
) {
    /** The endpoint of the Docker engine. */
    fun resolve(): DockerEndpoint {
        val uri = environment(DOCKER_HOST)?.trim()?.takeIf { it.isNotEmpty() } ?: contextEndpoint() ?: platformDefault()
        return if (LOCAL_SCHEMES.any { uri.startsWith(it) }) DockerEndpoint.Local(uri) else DockerEndpoint.NotLocal(uri)
    }

    /** The endpoint of the current Docker context, or null if there is none or it is not described. */
    private fun contextEndpoint(): String? {
        val config = json(home.resolve(".docker").resolve("config.json")) ?: return null
        val current = (config["currentContext"] as? WireValue.WireString)?.value ?: return null
        val contexts = home.resolve(".docker").resolve("contexts").resolve("meta")
        if (!contexts.isDirectory()) return null
        return runCatching { contexts.listDirectoryEntries() }.getOrDefault(emptyList()).sorted()
            .mapNotNull { json(it.resolve("meta.json")) }
            .firstOrNull { (it["Name"] as? WireValue.WireString)?.value == current }
            ?.let { meta -> ((meta["Endpoints"] as? WireValue.WireObject)?.get("docker") as? WireValue.WireObject)?.get("Host") }
            ?.let { (it as? WireValue.WireString)?.value }
    }

    /** The platform's default endpoint. */
    private fun platformDefault(): String {
        val system = operatingSystem.lowercase()
        val desktopSocket = home.resolve(".docker").resolve("run").resolve("docker.sock")
        return when {
            system.startsWith("windows") -> WINDOWS_PIPE
            system.startsWith("mac") && Files.exists(desktopSocket) -> "unix://${desktopSocket.toAbsolutePath()}"
            else -> UNIX_SOCKET
        }
    }

    /** The JSON object in [file], or null if the file is missing or holds no JSON object. */
    private fun json(file: Path): WireValue.WireObject? {
        if (!file.isRegularFile()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return ((RuntimeJson.read(text) as? JsonReading.Read)?.value as? WireValue.WireObject)
    }

    private companion object {
        /** The environment variable that names the engine. */
        const val DOCKER_HOST = "DOCKER_HOST"

        /** The default socket on Linux and macOS. */
        const val UNIX_SOCKET = "unix:///var/run/docker.sock"

        /** The default named pipe on Windows. */
        const val WINDOWS_PIPE = "npipe:////./pipe/docker_engine"

        /** The schemes of local endpoints. */
        val LOCAL_SCHEMES = listOf("unix://", "npipe://")
    }
}
