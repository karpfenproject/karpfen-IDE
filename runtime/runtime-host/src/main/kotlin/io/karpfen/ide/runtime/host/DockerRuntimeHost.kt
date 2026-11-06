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
import io.karpfen.ide.runtime.host.docker.BuildProgress
import io.karpfen.ide.runtime.host.docker.RuntimeConfiguration
import io.karpfen.ide.runtime.host.docker.RuntimeImage
import io.karpfen.ide.runtime.host.docker.RuntimeLabels
import io.karpfen.ide.runtime.host.docker.WorkspaceContainer
import io.karpfen.ide.runtime.host.docker.engine.ContainerSummary
import io.karpfen.ide.runtime.host.docker.engine.DockerEndpoint
import io.karpfen.ide.runtime.host.docker.engine.DockerEngine
import io.karpfen.ide.runtime.host.docker.engine.DockerEngineException
import io.karpfen.ide.runtime.protocol.api.RuntimeResult
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * The runtime of one workspace in a Docker container the IDE owns, built from the runtime sources bundled with the IDE.
 *
 * [ensureRunning] answers at once while the container runs; otherwise it checks the engine, adopts a running container
 * of this workspace built from the current sources or removes the workspace's other containers, builds the image if it
 * is missing, creates the container with its configuration, starts it and waits until the runtime answers and supports
 * the IDE. Calls are serialized, so a call during a start waits for it and then finds the runtime running. While the
 * container runs, a watcher notices when it stops; the next [ensureRunning] starts a new one. [stop] cancels a start
 * under way and removes the container. The host only lists, stops and removes images and containers labelled as the
 * IDE's runtime, and containers only of its own workspace.
 *
 * @param workspaceRoot the root folder of the workspace, which names and labels the container.
 * @param options what the host works with.
 */
internal class DockerRuntimeHost(workspaceRoot: Path, private val options: HostOptions) : RuntimeHost {

    override val kind: RuntimeHostKind = RuntimeHostKind.DOCKER

    /** The status as the host sets it. */
    private val state = MutableStateFlow<HostStatus>(HostStatus.Stopped)

    override val status: StateFlow<HostStatus> = state.asStateFlow()

    /** The host's lines and the container's log. */
    private val log = HostLog()

    override val logs: Flow<HostLogLine> = log.lines

    /** The name and labels of this workspace's container. */
    private val workspace = WorkspaceContainer(workspaceRoot)

    /** The intervals and bounds. */
    private val timings = options.timings

    /** Where the watcher and the log follower run. */
    private val scope = CoroutineScope(SupervisorJob() + options.dispatcher)

    /** Serializes starts and stops. */
    private val mutex = Mutex()

    /** The start under way, which [stop] cancels. */
    private val attempt = AtomicReference<Job?>(null)

    /** The engine the host is connected to, or null before the first check. */
    @Volatile
    private var connection: EngineConnection? = null

    /** The container the host created or adopted, or null; read and written under [mutex]. */
    private var container: OwnContainer? = null

    override suspend fun ensureRunning(): HostOutcome =
        mutex.withLock {
            runningOutcome()?.let { return@withLock it }
            coroutineScope {
                val start = async { start() }
                attempt.set(start)
                try {
                    start.await()
                } catch (cancelled: CancellationException) {
                    ensureActive()
                    HostOutcome.Failed(STOPPED_WHILE_STARTING)
                } finally {
                    attempt.set(null)
                }
            }
        }

    override suspend fun stop() {
        attempt.get()?.cancel()
        mutex.withLock {
            val current = container
            if (current == null) {
                state.value = HostStatus.Stopped
                return@withLock
            }
            state.value = HostStatus.Stopping
            log.host("Stopping the runtime container ${workspace.name}.")
            try {
                current.engine.stopContainer(current.id, timings.stopTimeout)
            } catch (failure: DockerEngineException) {
                log.host(failure.message.orEmpty())
            }
            try {
                current.engine.removeContainer(current.id)
                log.host("Removed the runtime container ${workspace.name}.")
            } catch (failure: DockerEngineException) {
                log.host(failure.message.orEmpty())
            }
            current.release()
            container = null
            state.value = HostStatus.Stopped
        }
    }

    override fun close() {
        runBlocking { withTimeoutOrNull(timings.closeBound) { stop() } }
        scope.cancel()
        connection?.engine?.close()
    }

    /** [HostOutcome.Ready] if the status is running and the container still runs, else null. */
    private suspend fun runningOutcome(): HostOutcome? {
        val running = state.value as? HostStatus.Running ?: return null
        val current = container ?: return null
        val stillRuns = try {
            current.engine.inspectContainer(current.id).running
        } catch (failure: DockerEngineException) {
            false
        }
        return if (stillRuns) HostOutcome.Ready(running.endpoint) else null
    }

    /** Starts the runtime step by step and answers where it is reached, or why there is none. */
    private suspend fun start(): HostOutcome {
        container?.release()
        container = null
        return try {
            val engine = checkEngine()
            val sources = bundledSources()
            val image = RuntimeImage(sources.contentHash, sources.runtimeCommit)
            val port = adopt(engine, image) ?: launch(engine, sources, image)
            val endpoint = RuntimeEndpoint(secure = false, host = LOOPBACK, port = port)
            awaitHealth(endpoint)
            val running = HostStatus.Running(endpoint, "Docker · image ${image.shortHash} · $LOOPBACK:$port")
            state.value = running
            container?.watch(running)
            log.host("The runtime is running at ${endpoint.httpBase}.")
            HostOutcome.Ready(endpoint)
        } catch (cancelled: CancellationException) {
            state.value = HostStatus.Stopped
            throw cancelled
        } catch (failure: StartFailure) {
            log.host(failure.problem.message)
            state.value = HostStatus.Failed(failure.problem)
            HostOutcome.Failed(failure.problem)
        }
    }

    /** Resolves the Docker endpoint and checks that the engine answers. */
    private suspend fun checkEngine(): DockerEngine {
        state.value = HostStatus.Preparing(HostStage.CHECKING_ENGINE)
        log.host("Checking the Docker engine.")
        val endpoint = options.resolveEndpoint()
        if (endpoint is DockerEndpoint.NotLocal) {
            throw StartFailure(
                HostProblemCode.ENGINE_NOT_LOCAL,
                "The Docker engine at ${endpoint.uri} is not on this machine.",
                "Use a local Docker engine, reached by a unix:// socket or an npipe:// pipe; a remote one would publish " +
                    "the runtime elsewhere.",
            )
        }
        val engine = engineAt(endpoint.uri)
        try {
            engine.ping()
        } catch (failure: DockerEngineException) {
            throw StartFailure(
                HostProblemCode.ENGINE_UNAVAILABLE,
                "No Docker engine answers at ${endpoint.uri}: ${failure.message}",
                ENGINE_HINT,
            )
        }
        return engine
    }

    /** The engine at [uri], connected anew if the endpoint changed since the last check. */
    private fun engineAt(uri: String): DockerEngine {
        connection?.takeIf { it.uri == uri }?.let { return it.engine }
        connection?.engine?.close()
        connection = null
        val engine = try {
            options.connectEngine(uri)
        } catch (failure: RuntimeException) {
            throw StartFailure(HostProblemCode.ENGINE_UNAVAILABLE, "Cannot connect to the Docker engine at $uri: ${failure.message}", ENGINE_HINT)
        }
        connection = EngineConnection(uri, engine)
        return engine
    }

    /** The runtime sources bundled with the IDE. */
    private fun bundledSources(): RuntimeSources =
        when (val lookup = options.sources()) {
            is SourcesLookup.Found -> lookup.sources
            is SourcesLookup.Missing -> throw StartFailure(
                HostProblemCode.SOURCES_MISSING,
                "Cannot build the runtime image: ${lookup.reason}.",
                "Build the IDE with its runtime submodule checked out (git submodule update --init --recursive).",
            )
        }

    /**
     * Adopts a running container of this workspace built from the current sources and answers its host port, after
     * removing every other container of this workspace; answers null if there is none to adopt.
     */
    private suspend fun adopt(engine: DockerEngine, image: RuntimeImage): Int? {
        val found = containerCall { engine.listContainers(workspace.filter, includeStopped = true) }
        val adoptable = found.firstOrNull { it.running && it.labels[RuntimeLabels.RUNTIME_HASH] == image.contentHash }
        found.filter { it !== adoptable }.forEach { remove(engine, it) }
        if (adoptable == null) return null
        log.host("Adopting the running runtime container ${workspace.name}.")
        container = OwnContainer(adoptable.id, engine).also { it.follow() }
        return containerCall { engine.inspectContainer(adoptable.id) }.hostPort ?: throw noPort()
    }

    /** Removes the container [stale] of this workspace, stopping it first if it runs. */
    private suspend fun remove(engine: DockerEngine, stale: ContainerSummary) {
        val why = if (stale.running) "built from other runtime sources" else "stopped"
        log.host("Removing the runtime container ${stale.id.take(SHORT_ID)} of this workspace ($why).")
        containerCall {
            if (stale.running) engine.stopContainer(stale.id, timings.stopTimeout)
            engine.removeContainer(stale.id)
        }
    }

    /** Builds the image if needed, creates and starts the container and answers its host port. */
    private suspend fun launch(engine: DockerEngine, sources: RuntimeSources, image: RuntimeImage): Int {
        ensureImage(engine, sources, image)
        state.value = HostStatus.Preparing(HostStage.CREATING_CONTAINER)
        log.host("Creating the runtime container ${workspace.name}.")
        val id = containerCall { engine.createContainer(workspace.spec(image)) }
        container = OwnContainer(id, engine)
        containerCall { engine.copyToContainer(id, RuntimeConfiguration.FOLDER, RuntimeConfiguration.tar()) }
        state.value = HostStatus.Preparing(HostStage.STARTING)
        log.host("Starting the runtime container ${workspace.name}.")
        containerCall { engine.startContainer(id) }
        container?.follow()
        return containerCall { engine.inspectContainer(id) }.hostPort ?: throw noPort()
    }

    /** Builds [image] from [sources] unless the engine has it, then removes older images of the runtime. */
    private suspend fun ensureImage(engine: DockerEngine, sources: RuntimeSources, image: RuntimeImage) {
        val exists = try {
            engine.imageExists(image.tag)
        } catch (failure: DockerEngineException) {
            throw StartFailure(HostProblemCode.IMAGE_BUILD_FAILED, failure.message.orEmpty())
        }
        if (exists) {
            log.host("Using the runtime image ${image.tag}.")
            return
        }
        state.value = HostStatus.Preparing(HostStage.BUILDING_IMAGE)
        log.host(
            "Building the runtime image ${image.tag} from the bundled runtime sources (runtime ${sources.runtimeCommit}, " +
                "${sources.fileCount} files); the first build takes minutes.",
        )
        val progress = BuildProgress()
        try {
            sources.open().use { context ->
                engine.buildImage(context, image.tag, image.labels) { line ->
                    log.host(line)
                    state.value = HostStatus.Preparing(HostStage.BUILDING_IMAGE, progress.after(line))
                }
            }
        } catch (failure: DockerEngineException) {
            throw StartFailure(HostProblemCode.IMAGE_BUILD_FAILED, "Building the runtime image failed: ${failure.message}")
        } catch (failure: IOException) {
            throw StartFailure(HostProblemCode.SOURCES_MISSING, "Cannot read the bundled runtime sources: ${failure.message}")
        }
        log.host("Built the runtime image ${image.tag}.")
        removeOlderImages(engine, image)
    }

    /** Removes the images of the runtime built from other sources that no container uses; a failure is only logged. */
    private suspend fun removeOlderImages(engine: DockerEngine, image: RuntimeImage) {
        val older = try {
            val used = engine.listContainers(RuntimeLabels.OURS, includeStopped = true).map { it.imageId }.toSet()
            engine.listImages(RuntimeLabels.OURS).filter { it.labels[RuntimeLabels.RUNTIME_HASH] != image.contentHash && it.id !in used }
        } catch (failure: DockerEngineException) {
            log.host("Cannot look for older runtime images: ${failure.message}")
            return
        }
        older.forEach { old ->
            try {
                engine.removeImage(old.id)
                log.host("Removed the older runtime image ${old.id.removePrefix(SHA_256_PREFIX).take(SHORT_ID)}.")
            } catch (failure: DockerEngineException) {
                log.host(failure.message.orEmpty())
            }
        }
    }

    /** Polls the runtime at [endpoint] until it answers its health check, then checks its IDE support. */
    private suspend fun awaitHealth(endpoint: RuntimeEndpoint) {
        state.value = HostStatus.Preparing(HostStage.WAITING_FOR_HEALTH)
        log.host("Waiting for the runtime at ${endpoint.httpBase} to answer.")
        val support = options.clients(endpoint).use { client ->
            withTimeoutOrNull(timings.healthTimeout) {
                while (client.health() !is RuntimeResult.Ok) delay(timings.healthPoll)
                client.checkIdeSupport()
            }
        }
        when (support) {
            null -> throw StartFailure(
                HostProblemCode.UNHEALTHY,
                "The runtime did not answer its health check within ${timings.healthTimeout.inWholeSeconds} s. Its last log lines:\n" +
                    log.last(UNHEALTHY_LOG_LINES).joinToString("\n"),
                "Look at the runtime log; stop the runtime and run again.",
            )
            is RuntimeResult.Failed -> throw StartFailure(
                HostProblemCode.NO_IDE_SUPPORT,
                "The runtime at ${endpoint.httpBase} lacks IDE support: ${support.failure.describe()}",
                "The runtime needs the branch with IDE support.",
            )
            is RuntimeResult.Ok -> Unit
        }
    }

    /** Runs the engine call [block] of a container step; a failure fails the start with [HostProblemCode.CONTAINER_FAILED]. */
    private suspend fun <T> containerCall(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: DockerEngineException) {
            throw StartFailure(HostProblemCode.CONTAINER_FAILED, failure.message.orEmpty())
        }

    /** The failure of a container that publishes no port. */
    private fun noPort(): StartFailure =
        StartFailure(HostProblemCode.CONTAINER_FAILED, "The runtime container ${workspace.name} publishes no port for the runtime.")

    /**
     * The engine at [uri].
     *
     * @property uri the endpoint.
     * @property engine the connected engine.
     */
    private class EngineConnection(val uri: String, val engine: DockerEngine)

    /**
     * The container [id] that the host created or adopted, with the coroutines that follow its log and watch it.
     *
     * @property id the container id.
     * @property engine the engine that runs it.
     */
    private inner class OwnContainer(val id: String, val engine: DockerEngine) {
        /** The coroutines of this container. */
        private val jobs = ArrayList<Job>()

        /** Follows the container's log into the host's log. */
        fun follow() {
            jobs += scope.launch {
                try {
                    engine.followLogs(id).collect { line ->
                        log.add(HostLogLine(line.text, if (line.fromStderr) LogStream.STDERR else LogStream.STDOUT))
                    }
                } catch (failure: DockerEngineException) {
                    log.host(failure.message.orEmpty())
                }
            }
        }

        /** Checks the container every watch interval and fails [running] with its exit code once it no longer runs. */
        fun watch(running: HostStatus.Running) {
            jobs += scope.launch {
                while (true) {
                    delay(timings.watchInterval)
                    val inspection = try {
                        engine.inspectContainer(id)
                    } catch (failure: DockerEngineException) {
                        null
                    }
                    if (inspection?.running == true) continue
                    val ending = inspection?.exitCode?.let { "with exit code $it" } ?: "and is gone"
                    val problem = HostProblem(
                        HostProblemCode.CONTAINER_DIED,
                        "The runtime container ${workspace.name} stopped $ending.",
                        "Run again to start a new runtime container; the runtime log tells why it stopped.",
                    )
                    if (state.compareAndSet(running, HostStatus.Failed(problem))) log.host(problem.message)
                    return@launch
                }
            }
        }

        /** Cancels the follower and the watcher. */
        fun release() {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }
    }

    /**
     * Ends a start with [problem].
     *
     * @property problem why the host has no runtime.
     */
    private class StartFailure(val problem: HostProblem) : Exception(problem.message) {
        constructor(code: HostProblemCode, message: String, hint: String? = null) : this(HostProblem(code, message, hint))
    }

    private companion object {
        /** The address the container's port is published on, and the runtime reached at. */
        const val LOOPBACK = "127.0.0.1"

        /** The number of characters of an id the log shows. */
        const val SHORT_ID = 12

        /** The prefix of image ids. */
        const val SHA_256_PREFIX = "sha256:"

        /** The number of log lines the problem of an unhealthy runtime quotes. */
        const val UNHEALTHY_LOG_LINES = 20

        /** What the user can do when no engine answers. */
        const val ENGINE_HINT = "Start Docker Desktop or the local Docker engine, then try again."

        /** The answer to a start that [stop] cancelled. */
        val STOPPED_WHILE_STARTING = HostProblem(
            HostProblemCode.CONTAINER_FAILED,
            "The runtime was stopped before it was ready.",
            "Start the runtime again.",
        )
    }
}
