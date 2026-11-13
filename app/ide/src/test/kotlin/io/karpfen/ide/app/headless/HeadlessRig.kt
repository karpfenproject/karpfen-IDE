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
package io.karpfen.ide.app.headless

import io.karpfen.ide.app.IdeApplication
import io.karpfen.ide.app.IdeSession
import io.karpfen.ide.app.e2e.RecordingFileOperations
import io.karpfen.ide.app.e2e.SilentFileWatcher
import io.karpfen.ide.app.e2e.UnusedFolderChooser
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.HostOutcome
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHost
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.protocol.RuntimeClient
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.session.Preparation
import io.karpfen.ide.runtime.session.PreparedDeployment
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.RuntimeSessionOptions
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.fixtures.FakeRuntimeSession
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.time.TestTimeSource

/**
 * The headless mode as [HeadlessLaunch] wires it, with test values for everything that depends on time, threads or a
 * runtime. A folder opens through `IdeApplication.prepare` over `defaultWorkbenchServices` with [ReadOnlySettings], as
 * in production. One test dispatcher on [scheduler] runs the workbench, the workspace, its analysis and its I/O. The
 * runtime hosts are [ScriptedHosts], and the sessions prepare for real and launch [FakeRuntimeSession]s through
 * [ScriptedSessions]. The console writes into two buffers, [output] and [errors].
 *
 * @param scheduler the virtual time of the test.
 * @param settingsDirectory the directory of the user's settings, which the headless mode never reads or writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HeadlessRig(val scheduler: TestCoroutineScheduler, private val settingsDirectory: Path) : AutoCloseable {
    /** The one test dispatcher on [scheduler] that runs all of the IDE's work. */
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)

    /** The scope that drives the workbench, as the thread of the headless mode does. */
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    /** What happened to hosts and sessions, in order, such as `shutdown lamp` and `close host`. */
    val events = ArrayList<String>()

    /** The runtime hosts the workspace gets. */
    val hosts = ScriptedHosts(events)

    /** The sessions the runs launch. */
    val sessions = ScriptedSessions(
        RuntimeSessions(RuntimeSessionOptions(dispatcher = dispatcher, timeSource = scheduler.timeSource, random = Random(SESSION_SEED))),
        events,
    )

    private val standardOutput = ByteArrayOutputStream()
    private val standardError = ByteArrayOutputStream()

    /** The console of the commands, over the two buffers. */
    val console = HeadlessConsole(PrintStream(standardOutput, true, Charsets.UTF_8), PrintStream(standardError, true, Charsets.UTF_8))

    /** The lines written to standard output so far. */
    val output: List<String> get() = linesOf(standardOutput)

    /** The lines written to standard error so far. */
    val errors: List<String> get() = linesOf(standardError)

    private val workspaceOptions = WorkspaceOptions(
        ioDispatcher = dispatcher,
        analysisDispatcher = dispatcher,
        sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
        timeSource = TestTimeSource(),
    )

    private val application = IdeApplication(
        services = { languageToolsOutput ->
            defaultWorkbenchServices(
                languageToolsOutput = languageToolsOutput,
                settingsDirectory = settingsDirectory,
                backgroundDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                workspaceOptions = workspaceOptions,
                fileWatcher = SilentFileWatcher(),
                clock = FIXED_CLOCK,
                fileOperations = RecordingFileOperations(),
                folderChooser = UnusedFolderChooser(),
                runtimeSessions = sessions,
                runtimeHosts = hosts,
                settings = ReadOnlySettings(dispatcher),
            )
        },
    )

    /** Opens a folder as `HeadlessLaunch` does, which is `IdeApplication.prepare`. */
    val prepare: suspend (CoroutineScope, Path) -> IdeSession = { scope, folder -> application.prepare(scope, folder) }

    /** Starts `validate` of [command] in [scope]. Its status is there once [settle] let it finish. */
    fun validate(command: HeadlessCommand.Validate): Deferred<Int> =
        scope.async { HeadlessValidation(console, prepare).validate(scope, command) }

    /** Starts `run` of [command] in [scope] with [stop]. Its status is there once it ended and [settle] let it finish. */
    fun run(command: HeadlessCommand.Run, stop: HeadlessStop): Deferred<Int> =
        scope.async { HeadlessRun(console, prepare, stop).run(scope, command) }

    /** Runs everything due on the virtual clock. */
    fun settle() = scheduler.advanceUntilIdle()

    override fun close() {
        scope.cancel()
    }

    companion object {
        /** The clock of the workbench. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-13T08:30:00Z"), ZoneOffset.UTC)

        /** The seed of the sessions' random, so the client ids are the same in every run. */
        const val SESSION_SEED: Int = 7

        /** The lines in [buffer], without the empty one after the last line break. */
        private fun linesOf(buffer: ByteArrayOutputStream): List<String> =
            buffer.toString(Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }
    }
}

/**
 * Runtime hosts that answer [outcome] and show the status the test sets. Each host notes its closing in [events]. They
 * start and stop nothing.
 */
internal class ScriptedHosts(private val events: MutableList<String>) : RuntimeHostFactory {
    /** What every host answers when a run asks for its runtime. */
    var outcome: HostOutcome = HostOutcome.Ready(ENDPOINT)

    /** The hosts created so far, oldest first. */
    val created = ArrayList<ScriptedHost>()

    override fun create(workspaceRoot: Path): RuntimeHost = ScriptedHost({ outcome }, events).also { created += it }

    companion object {
        /** The endpoint the hosts answer by default. */
        val ENDPOINT: RuntimeEndpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")
    }
}

/**
 * A runtime host that answers what [outcome] gives and shows [statusFlow]. It notes its closing in [events] as
 * [CLOSED].
 */
internal class ScriptedHost(private val outcome: () -> HostOutcome, private val events: MutableList<String>) : RuntimeHost {
    /** The status the test sets. */
    val statusFlow = MutableStateFlow<HostStatus>(HostStatus.Stopped)

    override val status: StateFlow<HostStatus> get() = statusFlow

    override val logs: Flow<HostLogLine> = emptyFlow()

    override suspend fun ensureRunning(): HostOutcome = outcome()

    override suspend fun stop() = Unit

    override fun close() {
        events += CLOSED
    }

    companion object {
        /** What a host notes in the events when it is closed. */
        const val CLOSED: String = "close host"
    }
}

/**
 * Sessions that prepare like [real] and launch a [FakeRuntimeSession] each time, which the test then drives through
 * its writable flows. The options of every launch are kept, and the shutdown of a session is noted in [record] as
 * `shutdown` and the name of its plan, such as `shutdown lamp`.
 */
internal class ScriptedSessions(private val real: RuntimeSessions, private val record: MutableList<String>) : RuntimeSessions {
    /** The sessions launched so far, oldest first. */
    val launched = ArrayList<FakeRuntimeSession>()

    /** The options of every launch, oldest first. */
    val options = ArrayList<DeployOptions>()

    override suspend fun prepare(workspace: Workspace, descriptor: Path): Preparation = real.prepare(workspace, descriptor)

    override fun launch(prepared: PreparedDeployment, client: RuntimeClient, scope: CoroutineScope, options: DeployOptions): RuntimeSession {
        this.options += options
        val fake = FakeRuntimeSession(prepared).also { launched += it }
        return object : RuntimeSession by fake {
            override suspend fun shutdown() {
                record += "shutdown ${prepared.plan.name}"
                fake.shutdown()
            }
        }
    }
}
