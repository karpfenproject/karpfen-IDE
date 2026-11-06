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

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.exception.NotModifiedException
import com.github.dockerjava.api.model.BuildResponseItem
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.StreamType
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration as JavaDuration
import kotlin.time.Duration

/**
 * The [DockerEngine] of the engine at [endpoint], through docker-java and its Apache HttpClient 5 transport. Every
 * call runs on `Dispatchers.IO`, and every failure of the client becomes a [DockerEngineException].
 *
 * @param endpoint the local endpoint, such as `unix:///var/run/docker.sock`, as [DockerEndpointResolver] finds it.
 */
internal class DockerJavaEngine(endpoint: String) : DockerEngine {

    /** The client of the engine. */
    private val client: DockerClient = run {
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(endpoint).build()
        val http = ApacheDockerHttpClient.Builder()
            .dockerHost(URI.create(endpoint))
            .sslConfig(config.sslConfig)
            .connectionTimeout(CONNECTION_TIMEOUT)
            .build()
        DockerClientImpl.getInstance(config, http)
    }

    override suspend fun ping() {
        call("ping the Docker engine") { client.pingCmd().exec() }
    }

    override suspend fun imageExists(tag: String): Boolean =
        call("look up the image $tag") {
            try {
                client.inspectImageCmd(tag).exec()
                true
            } catch (missing: NotFoundException) {
                false
            }
        }

    override suspend fun buildImage(context: InputStream, tag: String, labels: Map<String, String>, onLine: (String) -> Unit) {
        val callback = BuildOutput(onLine)
        call("build the image $tag") {
            client.buildImageCmd()
                .withTarInputStream(context)
                .withTags(setOf(tag))
                .withLabels(labels)
                .withRemove(true)
                .withForcerm(true)
                .exec(callback)
        }
        try {
            call("build the image $tag") { runInterruptible { callback.awaitCompletion() } }
        } finally {
            callback.close()
        }
        callback.failure?.let { throw DockerEngineException(it) }
    }

    override suspend fun removeImage(id: String) {
        call("remove the image $id") { client.removeImageCmd(id).exec() }
    }

    override suspend fun listImages(labelFilter: Map<String, String>): List<ImageSummary> =
        call("list the images") {
            client.listImagesCmd().withLabelFilter(labelFilter).exec().map { image ->
                ImageSummary(image.id, image.labels.orEmpty())
            }
        }

    override suspend fun listContainers(labelFilter: Map<String, String>, includeStopped: Boolean): List<ContainerSummary> =
        call("list the containers") {
            client.listContainersCmd().withLabelFilter(labelFilter).withShowAll(includeStopped).exec().map { container ->
                ContainerSummary(container.id, container.imageId.orEmpty(), container.state == RUNNING, container.labels.orEmpty())
            }
        }

    override suspend fun createContainer(spec: ContainerSpec): String =
        call("create the container ${spec.name}") {
            val port = ExposedPort.tcp(spec.containerPort)
            client.createContainerCmd(spec.image)
                .withName(spec.name)
                .withLabels(spec.labels)
                .withExposedPorts(port)
                .withStopTimeout(spec.stopTimeout.inWholeSeconds.toInt())
                .withHostConfig(
                    HostConfig.newHostConfig()
                        .withPortBindings(PortBinding(Ports.Binding.bindIp(spec.hostIp), port))
                        .withAutoRemove(spec.autoRemove),
                )
                .exec()
                .id
        }

    override suspend fun copyToContainer(id: String, path: String, tarBytes: ByteArray) {
        call("copy files into the container $id") {
            client.copyArchiveToContainerCmd(id).withRemotePath(path).withTarInputStream(ByteArrayInputStream(tarBytes)).exec()
        }
    }

    override suspend fun startContainer(id: String) {
        call("start the container $id") { client.startContainerCmd(id).exec() }
    }

    override suspend fun inspectContainer(id: String): ContainerInspection =
        call("inspect the container $id") {
            val response = try {
                client.inspectContainerCmd(id).exec()
            } catch (missing: NotFoundException) {
                return@call GONE
            }
            val bindings = response.networkSettings?.ports?.bindings.orEmpty()
            val hostPort = bindings.entries
                .firstOrNull { (port, _) -> port.port == CONTAINER_PORT }
                ?.value?.firstNotNullOfOrNull { it.hostPortSpec?.toIntOrNull() }
            ContainerInspection(response.state?.running == true, response.state?.exitCodeLong, hostPort)
        }

    override fun followLogs(id: String): Flow<ContainerOutputLine> =
        callbackFlow {
            val lines = LogLines()
            val callback = object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    lines.add(frame).forEach { trySendBlocking(it) }
                }

                override fun onError(throwable: Throwable) {
                    super.onError(throwable)
                    channel.close(DockerEngineException("The log of the container $id broke off: ${throwable.message}", throwable))
                }

                override fun onComplete() {
                    super.onComplete()
                    lines.rest().forEach { trySendBlocking(it) }
                    channel.close()
                }
            }
            try {
                client.logContainerCmd(id).withStdOut(true).withStdErr(true).withFollowStream(true).exec(callback)
            } catch (failure: RuntimeException) {
                throw DockerEngineException("Cannot read the log of the container $id: ${failure.message}", failure)
            }
            awaitClose { callback.close() }
        }.flowOn(Dispatchers.IO)

    override suspend fun stopContainer(id: String, timeout: Duration) {
        call("stop the container $id") {
            try {
                client.stopContainerCmd(id).withTimeout(timeout.inWholeSeconds.toInt()).exec()
            } catch (alreadyStopped: NotModifiedException) {
                Unit
            }
        }
    }

    override suspend fun removeContainer(id: String) {
        call("remove the container $id") {
            try {
                client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec()
            } catch (missing: NotFoundException) {
                Unit
            }
        }
    }

    override fun close() {
        client.close()
    }

    /**
     * Runs the blocking client call [block] on `Dispatchers.IO` and turns a failure of the client into a
     * [DockerEngineException] that says what could not be done ([what]).
     */
    private suspend fun <T> call(what: String, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DockerEngineException) {
                throw failure
            } catch (failure: Exception) {
                throw DockerEngineException("Cannot $what: ${failure.message ?: failure.javaClass.simpleName}", failure)
            }
        }

    /**
     * Collects the output of an image build: hands every line of its stream and its status messages, but not the
     * progress ticks of downloads, to [onLine], and keeps the last error the build reported.
     */
    private class BuildOutput(private val onLine: (String) -> Unit) : ResultCallback.Adapter<BuildResponseItem>() {
        /** The last error the build reported, or null if it reported none. */
        @Volatile
        var failure: String? = null
            private set

        override fun onNext(item: BuildResponseItem) {
            item.stream?.lineSequence()?.map { it.trimEnd('\r') }?.filter { it.isNotBlank() }?.forEach(onLine)
            item.status?.takeIf { it.isNotBlank() && item.progressDetail?.current == null }
                ?.let { status -> onLine(listOfNotNull(item.id, status).joinToString(": ")) }
            if (item.isErrorIndicated) {
                val error = item.errorDetail?.message ?: "the build failed"
                onLine(error)
                failure = error.lineSequence().lastOrNull { it.isNotBlank() } ?: error
            }
        }
    }

    /** Cuts the frames of a container's log into lines, keeping a started line of each stream until it ends. */
    private class LogLines {
        /** The started line of each stream. */
        private val started = HashMap<Boolean, StringBuilder>()

        /** The complete lines that [frame] ends. */
        fun add(frame: Frame): List<ContainerOutputLine> {
            val fromStderr = frame.streamType == StreamType.STDERR
            val buffer = started.getOrPut(fromStderr) { StringBuilder() }
            buffer.append(frame.payload.toString(Charsets.UTF_8))
            val lines = ArrayList<ContainerOutputLine>()
            while (true) {
                val end = buffer.indexOf("\n")
                if (end < 0) break
                lines += ContainerOutputLine(buffer.substring(0, end).trimEnd('\r'), fromStderr)
                buffer.delete(0, end + 1)
            }
            return lines
        }

        /** The started lines that the end of the log leaves unfinished. */
        fun rest(): List<ContainerOutputLine> =
            started.filterValues { it.isNotEmpty() }.map { (fromStderr, buffer) -> ContainerOutputLine(buffer.toString(), fromStderr) }
    }

    private companion object {
        /** The container state of a running container in a listing. */
        const val RUNNING = "running"

        /** The container port whose host port [inspectContainer] reads. */
        const val CONTAINER_PORT = 8080

        /** The inspection of a container that no longer exists. */
        val GONE = ContainerInspection(running = false, exitCode = null, hostPort = null)

        /** How long connecting to the engine may take. */
        val CONNECTION_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(5)
    }
}
