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

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.PreparedDeployment
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.runtime.session.SessionCommandOutcome
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.output.OutputState
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.settings.RuntimeSettings
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The runtime of one opened workspace and its runs, as the Runtime tool window shows and steers them. It holds the host
 * with its status and log, the runs with the selected one, and the commands.
 *
 * A run goes through these steps. It resolves the descriptor and saves every open file. It stops the earlier runs of
 * the same descriptor that still prepare, still wait for the runtime, or are live. It prepares the deployment from the
 * saved workspace and waits for the runtime. Then it launches a session that deploys, starts and observes the
 * environment. From then on the run's entry follows the session.
 *
 * Changing the runtime settings stops the live runs and replaces the host. The workbench creates one instance per
 * opened workspace and shuts it down when the workspace closes or the IDE exits.
 *
 * Use the class from the UI thread only. The commands launch their work in [scope] and return its job. The sessions
 * run on their own dispatcher, and their flows are collected in [scope].
 *
 * @param workspace the opened workspace.
 * @param sessions prepares deployments and launches sessions.
 * @param hosts creates the host of the workspace from the settings.
 * @param settings the IDE settings, which hold the runtime settings and receive the host selector's choice.
 * @param clients creates the client of a runtime endpoint.
 * @param support the active editor, the save-all flow, the dialogs and the tool window of the workbench.
 * @param ioDispatcher where a host is closed, which blocks.
 * @param clock the time of the log lines.
 * @property runShortcut the Run command's shortcut in words, which the empty tool window shows.
 */
public class RuntimeState internal constructor(
    private val scope: CoroutineScope,
    private val workspace: Workspace,
    private val sessions: RuntimeSessions,
    hosts: RuntimeHostFactory,
    private val settings: SettingsRepository,
    clients: (RuntimeEndpoint) -> RuntimeClient,
    private val support: RunSupport,
    ioDispatcher: CoroutineDispatcher,
    clock: Clock,
    public val runShortcut: String,
) {
    private val buffer = RingOutputBuffer(capacity = LOG_LINES)
    private val binding = RuntimeHostBinding(
        scope, workspace.root, hosts, settings.settings.value.runtime, clients, buffer, ioDispatcher, clock,
    )
    private val list = RunList()
    private val resolver = DescriptorResolver(workspace.root, support)
    private val launched = HashMap<Long, Launched>()
    private val pending = HashMap<Long, Job>()
    private var nextId = 1L

    /** What the host of the workspace is doing. */
    public val host: StateFlow<HostStatus> get() = binding.status

    /** The runtime settings, which the host selector shows. */
    public val hostSettings: StateFlow<RuntimeSettings> =
        settings.settings.map { it.runtime }.stateIn(scope, SharingStarted.Eagerly, settings.settings.value.runtime)

    /** The runs, newest first. */
    public val runs: StateFlow<List<RunEntry>> get() = list.runs

    /** The selected run, or null if no run is selected. */
    public val selected: StateFlow<RunEntry?> get() = list.selected

    /**
     * The run that Stop stops. That is the selected run if it can be stopped. Otherwise it is the only run that can be
     * stopped, and null if there are several or none.
     */
    public val stopTarget: StateFlow<RunEntry?> = combine(list.runs, list.selected) { runs, selected ->
        selected?.takeIf { it.isStoppable } ?: runs.filter { it.isStoppable }.singleOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** What Run picks without a descriptor given, which the empty tool window shows. */
    public val nextPick: StateFlow<DescriptorPick> = combine(support.activeFile, workspace.files) { active, tree ->
        DescriptorResolver.pick(active, descriptorsOf(tree))
    }.stateIn(scope, SharingStarted.Eagerly, DescriptorResolver.pick(support.activeFile.value, descriptorsOf(workspace.files.value)))

    /**
     * The runtime's log together with the host's account of preparing the runtime. It keeps at most [LOG_LINES] lines.
     */
    public val logs: OutputBuffer get() = buffer

    /** The Log tab over [logs]. */
    public val log: OutputState = OutputState(scope, buffer)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hostSettings.collect { next -> if (needsNewHost(next)) replaceHost(next) }
        }
    }

    /** Runs [descriptor], or the descriptor the workspace and the active editor pick, as the class describes. */
    public fun run(descriptor: Path? = null): Job = scope.launch { runFlow(descriptor) }

    /** Runs the descriptor of [entry] again. */
    public fun rerun(entry: RunEntry): Job = run(entry.descriptor)

    /** Stops the environment of [entry]. The entry then shows the run as stopped. */
    public fun stop(entry: RunEntry): Job = scope.launch { stopRun(entry.id) }

    /** Stops the [stopTarget], if there is one. Otherwise returns a job that is already complete. */
    public fun stopSelected(): Job = stopTarget.value?.let(::stop) ?: DONE

    /** Selects [entry]. */
    public fun select(entry: RunEntry) {
        list.select(entry.id)
    }

    /** Switches the observation of [entry]'s session on or off. */
    public fun setObservation(entry: RunEntry, on: Boolean): Job = scope.launch { entry.session?.setObservation(on) }

    /** Reads the model of [entry]'s session once. */
    public fun pull(entry: RunEntry): Job = scope.launch { entry.session?.pull() }

    /** Injects the event of [request] through [entry]'s session. */
    public suspend fun inject(entry: RunEntry, request: InjectionRequest): InjectionOutcome =
        entry.session?.events?.inject(request) ?: InjectionOutcome.Unavailable(InjectionAvailability.NotYet)

    /** Starts the runtime unless it already runs. */
    public fun startRuntime(): Job = scope.launch { binding.ensureRunning() }

    /** Stops every live run and then the runtime. */
    public fun stopRuntime(): Job = scope.launch {
        shutDownLiveRuns()
        binding.stop()
    }

    /** Removes the finished runs from the list. */
    public fun clearFinished() {
        list.clearFinished().forEach(::release)
    }

    /** What the tabs of the launched [entry] show, or null before the run was launched. */
    public fun details(entry: RunEntry): RunDetails? = launched[entry.id]?.details

    /** Chooses how the runtime is reached and writes it to the settings. */
    public fun selectHost(kind: RuntimeHostKind): Job =
        scope.launch { settings.update { it.copy(runtime = it.runtime.copy(host = kind)) } }

    /** Sets the URL of a runtime reached by URL and writes it to the settings. Surrounding blanks are trimmed. */
    public fun setUrl(url: String): Job = scope.launch { settings.update { it.copy(runtime = it.runtime.copy(url = url.trim())) } }

    /**
     * Shuts down every session and then closes the host. Sessions still busy after [SESSIONS_BOUND] are closed at once.
     * The state is closed afterwards.
     */
    public suspend fun shutdown() {
        withTimeoutOrNull(SESSIONS_BOUND) {
            coroutineScope { list.runs.value.mapNotNull { it.session }.forEach { launch { it.shutdown() } } }
        }
        launched.keys.toList().forEach { id -> list.entry(id)?.let(::release) }
        binding.close()
    }

    /**
     * Closes every session and the host at once, without waiting. This is the fallback when [shutdown] took too long
     * or never ran.
     */
    internal fun close() {
        list.runs.value.forEach(::release)
        binding.closeNow()
    }

    /** Wires the Log tab to whether the Runtime tool window is [visible]. Only the mediator calls it. */
    internal fun bindLogVisibility(visible: Flow<Boolean>) {
        log.bindVisibility(visible)
    }

    /** The steps of a run of [given], or of the picked descriptor when [given] is null. */
    private suspend fun runFlow(given: Path?) {
        val pick = DescriptorResolver.pick(support.activeFile.value, descriptorsOf(workspace.files.value))
        val descriptor = resolver.resolve(given, pick) ?: return
        if (!support.saveAll()) return
        stopRunsOf(descriptor)
        val id = nextId++
        list.add(RunEntry(id, descriptor, RunEntry.nameOf(descriptor), RunPhase.PREPARING)).forEach(::release)
        pending[id] = currentCoroutineContext().job
        try {
            show(id)
            when (val preparation = sessions.prepare(workspace, descriptor)) {
                is Preparation.Blocked -> list.update(id) { it.copy(phase = RunPhase.BLOCKED, findings = preparation.findings) }
                is Preparation.Ready -> deploy(id, preparation.prepared)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Waits for the runtime and launches the session of the run [id] that deploys [prepared]. */
    private suspend fun deploy(id: Long, prepared: PreparedDeployment) {
        list.update(id) { it.copy(planName = prepared.plan.name, phase = RunPhase.WAITING_FOR_RUNTIME) }
        when (val outcome = binding.ensureRunning()) {
            is HostOutcome.Failed -> list.update(id) { it.copy(phase = RunPhase.FAILED, hostProblem = outcome.problem) }
            is HostOutcome.Ready -> {
                if (list.entry(id) == null) return
                val client = binding.clientFor(outcome.endpoint)
                val session = sessions.launch(prepared, client, scope, DeployOptions(start = true, observe = true))
                list.update(id) { it.copy(phase = RunPhase.DEPLOYING, session = session) }
                launched[id] = Launched(RunDetails(session, scope) { request -> session.events.inject(request) }, follow(id, session))
                show(id)
            }
        }
    }

    /** Follows the state and the environment of [session] in the entry [id]. */
    private fun follow(id: Long, session: RuntimeSession): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(session.state, session.progress) { state, progress -> state to (environmentOf(state) ?: progress.environment) }
            .distinctUntilChanged()
            .collect { (state, environment) ->
                list.update(id) { it.copy(phase = RunPhase.of(state, it.phase), environment = environment ?: it.environment) }
            }
    }

    /** Stops the environment of the run [id], if it can be stopped. */
    private suspend fun stopRun(id: Long) {
        val entry = list.entry(id)?.takeIf { it.isStoppable } ?: return
        val session = entry.session ?: return
        if (session.stop() == SessionCommandOutcome.Done) list.update(id) { it.copy(phase = RunPhase.STOPPED) }
    }

    /**
     * Cancels the runs of [descriptor] that are not launched yet and shuts down the sessions of its live runs. Their
     * entries stay in the list as stopped.
     */
    private suspend fun stopRunsOf(descriptor: Path) {
        val runs = list.runs.value.filter { it.descriptor == descriptor }
        runs.forEach { entry ->
            pending.remove(entry.id)?.let { run ->
                run.cancel()
                list.update(entry.id) { it.copy(phase = RunPhase.STOPPED) }
            }
        }
        shutDown(runs.filter { it.isLive })
    }

    /** Shuts down the sessions of every live run. Their entries stay in the list as stopped. */
    private suspend fun shutDownLiveRuns() {
        shutDown(list.runs.value.filter { it.isLive })
    }

    /** Shuts down the sessions of [entries] together and marks them stopped. */
    private suspend fun shutDown(entries: List<RunEntry>) {
        coroutineScope { entries.mapNotNull { it.session }.forEach { launch { it.shutdown() } } }
        entries.forEach { entry -> list.update(entry.id) { it.copy(phase = RunPhase.STOPPED) } }
    }

    /** Stops the live runs and replaces the host with one for [next]. */
    private suspend fun replaceHost(next: RuntimeSettings) {
        shutDownLiveRuns()
        binding.replace(next)
    }

    /**
     * True if [next] reaches the runtime differently from the current host. A change of the URL only counts when the
     * runtime is reached by URL.
     */
    private fun needsNewHost(next: RuntimeSettings): Boolean {
        val current = binding.settings
        return next.host != current.host || (next.host == RuntimeHostKind.URL && next.url != current.url)
    }

    /** Selects the run [id] and shows the Runtime tool window. */
    private fun show(id: Long) {
        list.select(id)
        support.showRuntimeToolWindow()
    }

    /**
     * Stops following the run of [entry] and closes its session. Closing a session leaves its environment on the
     * runtime as it is.
     */
    private fun release(entry: RunEntry) {
        launched.remove(entry.id)?.let { run ->
            run.follower.cancel()
            run.details.close()
        }
        entry.session?.close()
    }

    /** The environment descriptors of [tree] in tree order. */
    private fun descriptorsOf(tree: WorkspaceTree): List<Path> = tree.files(FileKind.ENV_DESCRIPTOR).map { it.path }

    /** The environment of [state], or null for a state without one. */
    private fun environmentOf(state: SessionState): EnvironmentKey? = when (state) {
        is SessionState.Ready -> state.environment
        is SessionState.Running -> state.environment
        is SessionState.Halted -> state.environment
        is SessionState.Stopping -> state.environment
        is SessionState.Stopped -> state.environment
        SessionState.Deploying, is SessionState.Failed, SessionState.Closed -> null
    }

    /**
     * A launched run. It holds what the run's tabs show and the job that follows its session.
     *
     * @property details what the tabs show.
     * @property follower follows the session in the run's entry.
     */
    private class Launched(val details: RunDetails, val follower: Job)

    public companion object {
        /** How many lines the runtime log keeps. */
        public const val LOG_LINES: Int = 5_000

        /**
         * How long shutting down the sessions may take before they are closed at once. The bound leaves the host time
         * to stop afterwards.
         */
        internal val SESSIONS_BOUND: Duration = 5.seconds

        /** The job of a command with nothing to do. */
        private val DONE: Job = Job().apply { complete() }
    }
}
