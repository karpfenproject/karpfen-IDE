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
import io.karpfen.ide.ui.workbench.bridges.BridgeLoader
import io.karpfen.ide.ui.workbench.bridges.RunBridges
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.output.OutputState
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The runtime of one opened workspace and its runs, as the Runtime tool window shows and steers them. It holds the host
 * with its status and log, the runs with the selected one and its debugger, and the commands.
 *
 * A run goes through these steps. It resolves the descriptor. It saves every open file. It validates the whole project,
 * and a project with an error does not run: the report opens in a dialog that says the run is blocked. It asks in the
 * run dialog which bridge files to start with the run and whether to capture the run to a file, as [RunChoiceFlow]
 * describes. It stops the earlier runs of the same descriptor that still prepare, still wait for the runtime, or are
 * live. It prepares the deployment from the saved workspace and waits for the runtime. A preparation that blocks the
 * deployment, such as one whose context has errors, ends the run, and its entry lists the findings. Then it launches a
 * session that deploys, starts and observes the environment, and the chosen bridges, which start once the environment
 * runs. A captured run starts its [RunCapture] right before the launch. A run whose choice leaves the debugger out
 * deploys without the runtime's feature `Breakpoint`, as [RunChoice.debugger] describes. From then on the run's entry
 * follows the session.
 *
 * The capture of a run ends once the run is finished, which covers a stop, a failure and a replacement by a new run of
 * the same descriptor. It also ends when the run is cleared from the list or the workspace closes.
 *
 * The workbench creates one instance per opened workspace and shuts it down when the workspace closes or the IDE exits.
 *
 * Use the class from the UI thread only. The commands launch their work in [scope] and return its job. The sessions
 * run on their own dispatcher, and their flows are collected in [scope].
 *
 * @param workspace the opened workspace.
 * @param sessions prepares deployments and launches sessions.
 * @param hosts creates the host of the workspace.
 * @param settings the IDE settings, which remember the choices of the run dialog.
 * @param clients creates the client of a runtime endpoint.
 * @param support the active editor, the save-all flow, the dialogs and the tool window of the workbench.
 * @param ioDispatcher where a host is closed and the captures are written, which blocks.
 * @param clock the time of the log lines and of the start of a run.
 * @property runShortcut the Run command's shortcut in words, which the empty tool window shows.
 */
public class RuntimeState internal constructor(
    private val scope: CoroutineScope,
    private val workspace: Workspace,
    private val sessions: RuntimeSessions,
    hosts: RuntimeHostFactory,
    settings: SettingsRepository,
    clients: (RuntimeEndpoint) -> RuntimeClient,
    private val support: RunSupport,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    public val runShortcut: String,
) {
    private val buffer = RingOutputBuffer(capacity = LOG_LINES)
    private val binding = RuntimeHostBinding(
        scope, workspace.root, hosts, clients, buffer, ioDispatcher, clock,
    )
    private val list = RunList()
    private val resolver = DescriptorResolver(workspace.root, support)
    private val bridgeLoader = BridgeLoader(ioDispatcher)
    private val choices = RunChoiceFlow(workspace, settings, support)
    private val captures = RunCaptures(scope, workspace.root, binding, ioDispatcher, clock)
    private val launched = HashMap<Long, Launched>()
    private val pending = HashMap<Long, Job>()
    private var nextId = 1L

    /** What the host of the workspace is doing. */
    public val host: StateFlow<HostStatus> get() = binding.status

    /** The runs, newest first. */
    public val runs: StateFlow<List<RunEntry>> get() = list.runs

    /** The selected run, or null if no run is selected. */
    public val selected: StateFlow<RunEntry?> get() = list.selected

    /** The debugger of the selected run, with its pause, resume and step. */
    public val debugging: RunDebugging = RunDebugging(scope, list.selected)

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
            list.runs.collect { runs -> runs.filter { it.phase.isFinished }.forEach { launched[it.id]?.capture?.close() } }
        }
    }

    /** Runs [descriptor], or the descriptor the workspace and the active editor pick, as the class describes. */
    public fun run(descriptor: Path? = null): Job = scope.launch { runFlow(descriptor, null) }

    /**
     * Runs [descriptor] as [choice] says, without the run dialog. The steps are those the class describes, except that
     * the choice is given instead of asked for, and it is not remembered in the project. This is how a run is started
     * where nobody answers the run dialog, such as the headless mode of the IDE.
     */
    public fun run(descriptor: Path, choice: RunChoice): Job = scope.launch { runFlow(descriptor, choice) }

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

    /** The bridges of the launched [entry], or null before the run was launched. */
    public fun bridges(entry: RunEntry): RunBridges? = launched[entry.id]?.bridges

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

    /**
     * The steps of a run of [given], or of the picked descriptor when [given] is null. The run goes as [preset] says,
     * or as the run dialog answers when [preset] is null.
     */
    private suspend fun runFlow(given: Path?, preset: RunChoice?) {
        val pick = DescriptorResolver.pick(support.activeFile.value, descriptorsOf(workspace.files.value))
        val descriptor = resolver.resolve(given, pick) ?: return
        if (!support.saveAll()) return
        if (!support.validate()) return
        val choice = preset ?: choices.ask() ?: return
        stopRunsOf(descriptor)
        val started = clock.instant()
        val id = nextId++
        list.add(RunEntry(id, descriptor, RunEntry.nameOf(descriptor), RunPhase.PREPARING)).forEach(::release)
        pending[id] = currentCoroutineContext().job
        try {
            show(id)
            when (val preparation = sessions.prepare(workspace, descriptor)) {
                is Preparation.Blocked -> list.update(id) { it.copy(phase = RunPhase.BLOCKED, findings = preparation.findings) }
                is Preparation.Ready -> deploy(id, preparation.prepared, choice, started)
            }
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Waits for the runtime and launches the session of the run [id] that deploys [prepared], together with the
     * bridges of the [choice]. If the choice captures the run, the capture of the run that [started] then begins right
     * before the launch.
     */
    private suspend fun deploy(id: Long, prepared: PreparedDeployment, choice: RunChoice, started: Instant) {
        list.update(id) { it.copy(planName = prepared.plan.name, phase = RunPhase.WAITING_FOR_RUNTIME) }
        when (val outcome = binding.ensureRunning()) {
            is HostOutcome.Failed -> list.update(id) { it.copy(phase = RunPhase.FAILED, hostProblem = outcome.problem) }
            is HostOutcome.Ready -> {
                if (list.entry(id) == null) return
                val plans = withContext(ioDispatcher) { choice.bridges.map { bridgeLoader.plan(workspace.root, it) } }
                if (list.entry(id) == null) return
                val capture = if (choice.capture) capture(id, started, outcome) else null
                if (list.entry(id) == null) {
                    capture?.close()
                    return
                }
                val client = binding.clientFor(outcome.endpoint)
                val session = sessions.launch(prepared, client, scope, DeployOptions(
                    start = true,
                    observe = true,
                    debugger = choice.debugger,
                    trueParallel = choice.trueParallel,
                ))
                list.update(id) { it.copy(phase = RunPhase.DEPLOYING, session = session) }
                launched[id] = Launched(
                    RunDetails(session, scope) { request -> session.events.inject(request) },
                    RunBridges(plans, session, scope, bridgeLoader),
                    follow(id, session, capture),
                    capture,
                )
                show(id)
            }
        }
    }

    /**
     * Starts the capture of the run [id] that [started] then, on the runtime of [outcome]. Returns the capture, or null
     * if it could not start. The entry names the file or, once, the problem.
     */
    private suspend fun capture(id: Long, started: Instant, outcome: HostOutcome.Ready): RunCapture? {
        val descriptor = list.entry(id)?.descriptor ?: return null
        val capture = captures.start(descriptor, started, outcome.endpoint) { problem ->
            list.update(id) { it.copy(captureProblem = it.captureProblem ?: problem) }
        }
        capture?.let { list.update(id) { entry -> entry.copy(captureFile = it.file) } }
        return capture
    }

    /**
     * Follows the state and the environment of [session] in the entry [id]. The environment's key is also noted in
     * the run's [capture], if there is one.
     */
    private fun follow(id: Long, session: RuntimeSession, capture: RunCapture?): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        combine(session.state, session.progress) { state, progress -> state to (environmentOf(state) ?: progress.environment) }
            .distinctUntilChanged()
            .collect { (state, environment) ->
                environment?.let { capture?.environment(it) }
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

    /** Selects the run [id] and shows the Runtime tool window. */
    private fun show(id: Long) {
        list.select(id)
        support.showRuntimeToolWindow()
    }

    /**
     * Stops following the run of [entry], ends its capture, stops its bridges and closes its session. Closing a
     * session leaves its environment on the runtime as it is.
     */
    private fun release(entry: RunEntry) {
        launched.remove(entry.id)?.let { run ->
            run.follower.cancel()
            run.capture?.close()
            run.bridges.close()
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
     * A launched run. It holds what the run's tabs show, its bridges, the job that follows its session and its capture.
     *
     * @property details what the tabs show.
     * @property bridges the bridges that were started with the run.
     * @property follower follows the session in the run's entry.
     * @property capture the capture of the run's log, or null if the run is not captured.
     */
    private class Launched(val details: RunDetails, val bridges: RunBridges, val follower: Job, val capture: RunCapture?)

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
