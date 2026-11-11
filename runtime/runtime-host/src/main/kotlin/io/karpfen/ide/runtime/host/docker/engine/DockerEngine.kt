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

import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Docker engine as the runtime host uses it: the few calls that build the runtime image and run, watch and remove
 * its container. The host talks to Docker only through this port. That keeps the host's behaviour testable against a
 * fake, and it keeps the Docker client inside this package, in [DockerJavaEngine].
 *
 * Every suspending function may be called from any coroutine and never blocks its thread. The adapter runs the blocking
 * calls on `Dispatchers.IO`. Every call that fails throws [DockerEngineException]. A `CancellationException` of the
 * caller is rethrown as it is.
 */
internal interface DockerEngine : AutoCloseable {
    /** Checks that the engine answers. */
    suspend fun ping()

    /** True if the local image store holds an image tagged [tag]. */
    suspend fun imageExists(tag: String): Boolean

    /**
     * Builds the image [tag] with [labels] from the Docker build context [context], a tar archive (gzip-compressed or
     * not) with the `Dockerfile` at its root, and hands every line of the build output to [onLine], from the thread that
     * reads it.
     *
     * @throws DockerEngineException with the build's last error line if the build fails.
     */
    suspend fun buildImage(context: InputStream, tag: String, labels: Map<String, String>, onLine: (String) -> Unit)

    /** Removes the image [id]. It fails if a container still uses the image. */
    suspend fun removeImage(id: String)

    /** The images that carry every label of [labelFilter] with its value. */
    suspend fun listImages(labelFilter: Map<String, String>): List<ImageSummary>

    /** The containers that carry every label of [labelFilter] with its value, the stopped ones if [includeStopped]. */
    suspend fun listContainers(labelFilter: Map<String, String>, includeStopped: Boolean): List<ContainerSummary>

    /** Creates a container as [spec] describes, without starting it, and answers its id. */
    suspend fun createContainer(spec: ContainerSpec): String

    /** Unpacks the tar archive [tarBytes] into the folder [path] of the container [id]. */
    suspend fun copyToContainer(id: String, path: String, tarBytes: ByteArray)

    /** Starts the container [id]. */
    suspend fun startContainer(id: String)

    /**
     * The state of the container [id] and the host port published for its container port. A container that no longer
     * exists answers as not running, without exit code and port.
     */
    suspend fun inspectContainer(id: String): ContainerInspection

    /**
     * The log of the container [id], from its start, line by line, following it until the container stops or the
     * collector stops collecting. A failure ends the flow with [DockerEngineException].
     */
    fun followLogs(id: String): Flow<ContainerOutputLine>

    /** Stops the container [id] and kills it if it still runs after [timeout]. A container that already stopped stays as it is. */
    suspend fun stopContainer(id: String, timeout: Duration)

    /** Removes the container [id] with its anonymous volumes and kills it first if it still runs. A missing one is no error. */
    suspend fun removeContainer(id: String)

    /** Releases the connection to the engine. */
    override fun close()
}

/**
 * The container the host creates for a workspace.
 *
 * @property image the tag of the image.
 * @property name the container name.
 * @property labels the labels of the container.
 * @property hostIp the host address its container port is published on, only reachable from this machine.
 * @property containerPort the port the runtime listens on inside the container, published on an ephemeral host port.
 * @property autoRemove whether the engine removes the container when it stops. The host leaves it false and removes the
 *   container itself, so the container's log stays readable until then.
 * @property stopTimeout how long the engine waits for the runtime to stop before it kills it.
 */
internal data class ContainerSpec(
    val image: String,
    val name: String,
    val labels: Map<String, String>,
    val hostIp: String = "127.0.0.1",
    val containerPort: Int = 8080,
    val autoRemove: Boolean = false,
    val stopTimeout: Duration = 10.seconds,
)

/**
 * The state of a container.
 *
 * @property running true while the container runs.
 * @property exitCode the exit code of a stopped container, or null if it has none.
 * @property hostPort the host port published for the container port, or null if none is published, as for a container
 *   that does not run.
 */
internal data class ContainerInspection(val running: Boolean, val exitCode: Long?, val hostPort: Int?)

/**
 * A container as a listing shows it.
 *
 * @property id the container id.
 * @property imageId the id of its image.
 * @property running true while the container runs.
 * @property labels its labels.
 */
internal data class ContainerSummary(val id: String, val imageId: String, val running: Boolean, val labels: Map<String, String>)

/**
 * An image as a listing shows it.
 *
 * @property id the image id.
 * @property labels its labels.
 */
internal data class ImageSummary(val id: String, val labels: Map<String, String>)

/**
 * One line of a container's log.
 *
 * @property text the line without its line break.
 * @property fromStderr true if the container wrote it to its standard error, false for its standard output.
 */
internal data class ContainerOutputLine(val text: String, val fromStderr: Boolean)

/**
 * A call to the Docker engine failed.
 *
 * @param message what went wrong, in words for the user.
 * @param cause the failure of the Docker client, if any.
 */
internal class DockerEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
