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

import io.karpfen.ide.runtime.host.docker.engine.ContainerInspection
import io.karpfen.ide.runtime.host.docker.engine.ContainerOutputLine
import io.karpfen.ide.runtime.host.docker.engine.ContainerSpec
import io.karpfen.ide.runtime.host.docker.engine.ContainerSummary
import io.karpfen.ide.runtime.host.docker.engine.DockerEngine
import io.karpfen.ide.runtime.host.docker.engine.DockerEngineException
import io.karpfen.ide.runtime.host.docker.engine.ImageSummary
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import kotlin.time.Duration

/** A call the [FakeDockerEngine] received, with its arguments. */
internal sealed interface EngineCall {
    /** [DockerEngine.ping]. */
    data object Ping : EngineCall

    /** [DockerEngine.imageExists]. */
    data class ImageExists(val tag: String) : EngineCall

    /** [DockerEngine.buildImage], with the build context read as text. */
    data class BuildImage(val context: String, val tag: String, val labels: Map<String, String>) : EngineCall

    /** [DockerEngine.removeImage]. */
    data class RemoveImage(val id: String) : EngineCall

    /** [DockerEngine.listImages]. */
    data class ListImages(val labelFilter: Map<String, String>) : EngineCall

    /** [DockerEngine.listContainers]. */
    data class ListContainers(val labelFilter: Map<String, String>, val includeStopped: Boolean) : EngineCall

    /** [DockerEngine.createContainer]. */
    data class CreateContainer(val spec: ContainerSpec) : EngineCall

    /** [DockerEngine.copyToContainer]. */
    class CopyToContainer(val id: String, val path: String, val tarBytes: ByteArray) : EngineCall {
        override fun toString(): String = "CopyToContainer($id, $path, ${tarBytes.size} bytes)"
    }

    /** [DockerEngine.startContainer]. */
    data class StartContainer(val id: String) : EngineCall

    /** [DockerEngine.inspectContainer]. */
    data class InspectContainer(val id: String) : EngineCall

    /** [DockerEngine.followLogs]. */
    data class FollowLogs(val id: String) : EngineCall

    /** [DockerEngine.stopContainer]. */
    data class StopContainer(val id: String, val timeout: Duration) : EngineCall

    /** [DockerEngine.removeContainer]. */
    data class RemoveContainer(val id: String) : EngineCall

    /** [DockerEngine.close]. */
    data object Close : EngineCall
}

/**
 * A stand-in for the Docker engine that answers what each test programmed and records every call. It keeps no images
 * and no containers and never simulates Docker: building an image does not make it exist, and creating, starting or
 * stopping a container changes no answer. A test programs the answers it needs, changes them between steps and reads
 * the calls.
 */
internal class FakeDockerEngine : DockerEngine {
    /** Guards the records. */
    private val lock = Any()

    /** The calls so far. */
    private val recorded = ArrayList<EngineCall>()

    /** The failure messages per function name, such as `"ping"`; a programmed function throws [DockerEngineException]. */
    val failures: MutableMap<String, String> = HashMap()

    /** The answer of [imageExists]. */
    @Volatile
    var imageExists: Boolean = true

    /** The lines [buildImage] hands to its caller before it returns or fails. */
    @Volatile
    var buildOutput: List<String> = emptyList()

    /** The answer of [listImages]. */
    @Volatile
    var images: List<ImageSummary> = emptyList()

    /** The answer of [listContainers] for a label filter. */
    @Volatile
    var containers: (Map<String, String>) -> List<ContainerSummary> = { emptyList() }

    /** The answer of [createContainer]. */
    @Volatile
    var createdId: String = CREATED_ID

    /** The answer of [inspectContainer]. */
    @Volatile
    var inspection: ContainerInspection = ContainerInspection(running = true, exitCode = null, hostPort = HOST_PORT)

    /** The lines [followLogs] emits before it waits for the collector to stop. */
    @Volatile
    var logLines: List<ContainerOutputLine> = emptyList()

    /** The calls so far, in order. */
    val calls: List<EngineCall> get() = synchronized(lock) { recorded.toList() }

    /** The calls so far of type [T]. */
    inline fun <reified T : EngineCall> callsOf(): List<T> = calls.filterIsInstance<T>()

    override suspend fun ping() {
        record(EngineCall.Ping, "ping")
    }

    override suspend fun imageExists(tag: String): Boolean {
        record(EngineCall.ImageExists(tag), "imageExists")
        return imageExists
    }

    override suspend fun buildImage(context: InputStream, tag: String, labels: Map<String, String>, onLine: (String) -> Unit) {
        val call = EngineCall.BuildImage(context.readBytes().toString(Charsets.UTF_8), tag, labels)
        synchronized(lock) { recorded += call }
        buildOutput.forEach(onLine)
        failures["buildImage"]?.let { throw DockerEngineException(it) }
    }

    override suspend fun removeImage(id: String) {
        record(EngineCall.RemoveImage(id), "removeImage")
    }

    override suspend fun listImages(labelFilter: Map<String, String>): List<ImageSummary> {
        record(EngineCall.ListImages(labelFilter), "listImages")
        return images
    }

    override suspend fun listContainers(labelFilter: Map<String, String>, includeStopped: Boolean): List<ContainerSummary> {
        record(EngineCall.ListContainers(labelFilter, includeStopped), "listContainers")
        return containers(labelFilter)
    }

    override suspend fun createContainer(spec: ContainerSpec): String {
        record(EngineCall.CreateContainer(spec), "createContainer")
        return createdId
    }

    override suspend fun copyToContainer(id: String, path: String, tarBytes: ByteArray) {
        record(EngineCall.CopyToContainer(id, path, tarBytes), "copyToContainer")
    }

    override suspend fun startContainer(id: String) {
        record(EngineCall.StartContainer(id), "startContainer")
    }

    override suspend fun inspectContainer(id: String): ContainerInspection {
        record(EngineCall.InspectContainer(id), "inspectContainer")
        return inspection
    }

    override fun followLogs(id: String): Flow<ContainerOutputLine> =
        flow {
            record(EngineCall.FollowLogs(id), "followLogs")
            logLines.forEach { emit(it) }
            awaitCancellation()
        }

    override suspend fun stopContainer(id: String, timeout: Duration) {
        record(EngineCall.StopContainer(id, timeout), "stopContainer")
    }

    override suspend fun removeContainer(id: String) {
        record(EngineCall.RemoveContainer(id), "removeContainer")
    }

    override fun close() {
        synchronized(lock) { recorded += EngineCall.Close }
    }

    /** Records [call] and throws the failure programmed for [function], if any. */
    private fun record(call: EngineCall, function: String) {
        synchronized(lock) { recorded += call }
        failures[function]?.let { throw DockerEngineException(it) }
    }

    companion object {
        /** The default id of a created container. */
        const val CREATED_ID = "c-new"

        /** The default published host port. */
        const val HOST_PORT = 51_234
    }
}
