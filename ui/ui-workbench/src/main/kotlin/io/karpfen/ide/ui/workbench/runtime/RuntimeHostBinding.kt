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
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.LogStream
import io.karpfen.ide.runtime.host.RuntimeHost
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock

/**
 * The runtime host of one workspace and what depends on it. The host is created through [hosts] for [root].
 * The binding publishes the host's status and writes its log lines to [log]. It also hands every log line, unfiltered,
 * to the listeners that [observe] registers. It keeps one client for the runtime's current endpoint. The client is
 * created when the host reports the runtime as running. It is closed when the endpoint changes or the binding closes.
 *
 * Use the class from the UI thread only. The flows are collected in [scope]. Closing a host blocks, so it runs on
 * [ioDispatcher].
 *
 * @param root the root folder of the workspace.
 * @param clients creates the client of an endpoint.
 * @param clock the time of the log lines.
 */
internal class RuntimeHostBinding(
    private val scope: CoroutineScope,
    root: Path,
    hosts: RuntimeHostFactory,
    private val clients: (RuntimeEndpoint) -> RuntimeClient,
    private val log: OutputSink,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : RuntimeLogSource {
    private val hostStatus = MutableStateFlow<HostStatus>(HostStatus.Stopped)
    private val listeners = ArrayList<(HostLogLine) -> Unit>()
    private val host: RuntimeHost = hosts.create(root)
    private val following: List<Job> = follow(host)
    private var client: RuntimeClient? = null
    private var open = true

    /** What the host is doing. */
    val status: StateFlow<HostStatus> get() = hostStatus.asStateFlow()

    /**
     * Calls [listener] with every log line of the host, from now on and before any filtering, until the returned handle
     * is disposed.
     */
    override fun observe(listener: (HostLogLine) -> Unit): DisposableHandle {
        listeners.add(listener)
        return DisposableHandle { listeners.remove(listener) }
    }

    /** Starts the runtime unless it already runs. Returns where the runtime is reached, or why there is none. */
    suspend fun ensureRunning(): HostOutcome = host.ensureRunning()

    /** Stops the runtime of the host. */
    suspend fun stop() {
        host.stop()
    }

    /**
     * The client of the runtime at [endpoint]. It is the client already created for that endpoint, or a new one that
     * replaces and closes the previous client.
     */
    fun clientFor(endpoint: RuntimeEndpoint): RuntimeClient {
        client?.takeIf { it.endpoint == endpoint }?.let { return it }
        client?.close()
        return clients(endpoint).also { client = it }
    }

    /**
     * Closes the host and the client. Closing the host stops its runtime within the time bound of the host. Only the
     * first call has an effect.
     */
    suspend fun close() {
        release()
    }

    /**
     * Closes the client at once and hands the close of the host to [ioDispatcher] without waiting for it. Closing the
     * host may block until the host has stopped its container. The calling thread therefore never blocks. Only the
     * first call has an effect.
     */
    fun closeNow() {
        if (!open) return
        open = false
        detach()
        val closing = host
        CoroutineScope(ioDispatcher).launch { closing.close() }
    }

    /** Stops following the host, closes it on the I/O dispatcher and closes the client. */
    private suspend fun release() {
        if (!open) return
        open = false
        detach()
        val closing = host
        withContext(ioDispatcher) { closing.close() }
        hostStatus.value = HostStatus.Stopped
    }

    /** Stops following the host and closes its client. */
    private fun detach() {
        following.forEach(Job::cancel)
        client?.close()
        client = null
    }

    /**
     * Follows the status of [host] and writes its log lines to [log]. When the host reports a running runtime, the
     * client for its endpoint is created.
     */
    private fun follow(host: RuntimeHost): List<Job> = listOf(
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.status.collect { status ->
                hostStatus.value = status
                if (status is HostStatus.Running) clientFor(status.endpoint)
            }
        },
        scope.launch(start = CoroutineStart.UNDISPATCHED) { host.logs.collect(::write) },
    )

    /**
     * Hands [line] to the listeners and writes it to the log. Standard error becomes an error and everything else
     * becomes information. The logger name is the stream's name in lower case. The lines that the engine trace writes
     * on every tick are left out, as [EngineTrace.isNoise] tells. They would push everything else out of the log
     * within seconds.
     */
    private fun write(line: HostLogLine) {
        listeners.toList().forEach { it(line) }
        if (EngineTrace.isNoise(line.text)) return
        val level = if (line.stream == LogStream.STDERR) OutputLevel.ERROR else OutputLevel.INFO
        log.append(clock.instant(), level, line.stream.name.lowercase(), line.text, null)
    }
}
