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
import io.karpfen.ide.ui.workbench.settings.RuntimeSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock

/**
 * The runtime host of one workspace and what hangs on it: the host created through [hosts] from the settings, its
 * status, its log lines written to [log], and one client per endpoint of the runtime, created when the host reports it
 * running and closed when the endpoint changes or the binding closes. Replacing the settings closes the old host,
 * which stops its container, and creates the new one. Used from the UI thread; collected in [scope]; the blocking
 * close of a host runs on [ioDispatcher].
 *
 * @param root the root folder of the workspace.
 * @param settings the settings the first host is created from.
 * @param clients creates the client of an endpoint.
 * @param clock the time of the log lines.
 */
internal class RuntimeHostBinding(
    private val scope: CoroutineScope,
    private val root: Path,
    private val hosts: RuntimeHostFactory,
    settings: RuntimeSettings,
    private val clients: (RuntimeEndpoint) -> RuntimeClient,
    private val log: OutputSink,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val hostStatus = MutableStateFlow<HostStatus>(HostStatus.Stopped)
    private var host: RuntimeHost = hosts.create(root, settings.toHostSettings())
    private var following: List<Job> = follow(host)
    private var client: RuntimeClient? = null
    private var open = true

    /** The settings of the current host. */
    var settings: RuntimeSettings = settings
        private set

    /** What the current host is doing. */
    val status: StateFlow<HostStatus> get() = hostStatus.asStateFlow()

    /** Starts the runtime unless it runs, and answers where it is reached or why there is none. */
    suspend fun ensureRunning(): HostOutcome = host.ensureRunning()

    /** Stops the runtime of the current host. */
    suspend fun stop() {
        host.stop()
    }

    /** The client of the runtime at [endpoint]: the one created for it, or a new one that replaces the client before. */
    fun clientFor(endpoint: RuntimeEndpoint): RuntimeClient {
        client?.takeIf { it.endpoint == endpoint }?.let { return it }
        client?.close()
        return clients(endpoint).also { client = it }
    }

    /** Closes the current host and its client and creates the host of [next]. */
    suspend fun replace(next: RuntimeSettings) {
        release()
        settings = next
        host = hosts.create(root, next.toHostSettings())
        following = follow(host)
        open = true
    }

    /** Closes the host, which stops its runtime within the host's bound, and the client; once only. */
    suspend fun close() {
        release()
    }

    /** Closes the host and the client at once, without waiting on another thread. */
    fun closeNow() {
        if (!open) return
        open = false
        following.forEach(Job::cancel)
        client?.close()
        client = null
        host.close()
    }

    /** Stops following the host, closes it on the I/O dispatcher and closes the client. */
    private suspend fun release() {
        if (!open) return
        open = false
        following.forEach(Job::cancel)
        client?.close()
        client = null
        val closing = host
        withContext(ioDispatcher) { closing.close() }
        hostStatus.value = HostStatus.Stopped
    }

    /** Follows the status of [host], creating the client of a running runtime, and writes its log lines to [log]. */
    private fun follow(host: RuntimeHost): List<Job> = listOf(
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.status.collect { status ->
                hostStatus.value = status
                if (status is HostStatus.Running) clientFor(status.endpoint)
            }
        },
        scope.launch(start = CoroutineStart.UNDISPATCHED) { host.logs.collect(::write) },
    )

    /** Writes [line] to the log: standard error as errors, everything else as information. */
    private fun write(line: HostLogLine) {
        val level = if (line.stream == LogStream.STDERR) OutputLevel.ERROR else OutputLevel.INFO
        log.append(clock.instant(), level, line.stream.name.lowercase(), line.text, null)
    }
}
