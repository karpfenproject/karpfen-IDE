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
package io.karpfen.ide.ui.workbench.testing

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHost
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.host.RuntimeHostSettings
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.PreparedDeployment
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.fixtures.FakeRuntimeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * A runtime host that shows the status, answers the start outcome and emits the log lines that its test programs. It
 * counts what it was asked. It starts and stops nothing.
 *
 * @property settings the settings it was created with.
 * @param lifecycle where it records that it was closed.
 */
class FakeRuntimeHost(
    val settings: RuntimeHostSettings,
    var outcome: HostOutcome,
    private val lifecycle: MutableList<String> = ArrayList(),
) : RuntimeHost {
    /** The status the test sets. */
    val statusFlow = MutableStateFlow<HostStatus>(HostStatus.Stopped)

    /**
     * When set, [ensureRunning] answers [outcome] only once the test completes it, as a host that is still starting.
     */
    var held: CompletableDeferred<Unit>? = null

    /** Where the test emits log lines. Recent lines are replayed to new collectors. */
    val logLines = MutableSharedFlow<HostLogLine>(replay = LOG_REPLAY)

    /** How often [ensureRunning] was called. */
    var starts = 0
        private set

    /** How often [stop] was called. */
    var stops = 0
        private set

    /** How often [close] was called. */
    var closes = 0
        private set

    override val kind: RuntimeHostKind get() = settings.kind

    override val status: StateFlow<HostStatus> get() = statusFlow.asStateFlow()

    override val logs: Flow<HostLogLine> get() = logLines.asSharedFlow()

    override suspend fun ensureRunning(): HostOutcome {
        starts++
        held?.await()
        return outcome
    }

    override suspend fun stop() {
        stops++
    }

    override fun close() {
        closes++
        lifecycle += "close ${settings.kind}"
    }

    companion object {
        /** The endpoint the hosts answer by default. */
        val ENDPOINT: RuntimeEndpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

        private const val LOG_REPLAY = 64
    }
}

/** Creates [FakeRuntimeHost]s answering [outcome] and keeps them in creation order. */
class FakeRuntimeHosts(var outcome: HostOutcome = HostOutcome.Ready(FakeRuntimeHost.ENDPOINT)) : RuntimeHostFactory {
    /** The hosts created so far, oldest first. */
    val created = ArrayList<FakeRuntimeHost>()

    /** The roots the hosts were created for, oldest first. */
    val roots = ArrayList<Path>()

    /** The creation and the close of every host by its kind, such as `create DOCKER`, in the order they happened. */
    val lifecycle = ArrayList<String>()

    /** The host created last. */
    val current: FakeRuntimeHost get() = created.last()

    override fun create(workspaceRoot: Path, settings: RuntimeHostSettings): RuntimeHost {
        roots.add(workspaceRoot)
        lifecycle += "create ${settings.kind}"
        return FakeRuntimeHost(settings, outcome, lifecycle).also { created += it }
    }
}

/**
 * Sessions that prepare like [real] and launch a [FakeRuntimeSession] each time. The test then drives each session
 * through its writable flows. The launches are recorded.
 */
class ScriptedRuntimeSessions(private val real: RuntimeSessions) : RuntimeSessions {
    /** The sessions launched so far, oldest first. */
    val launched = ArrayList<FakeRuntimeSession>()

    /** The options of every launch, oldest first. */
    val options = ArrayList<DeployOptions>()

    override suspend fun prepare(workspace: Workspace, descriptor: Path): Preparation = real.prepare(workspace, descriptor)

    override fun launch(
        prepared: PreparedDeployment,
        client: RuntimeClient,
        scope: CoroutineScope,
        options: DeployOptions,
    ): RuntimeSession {
        this.options += options
        return FakeRuntimeSession(prepared).also { launched += it }
    }
}
