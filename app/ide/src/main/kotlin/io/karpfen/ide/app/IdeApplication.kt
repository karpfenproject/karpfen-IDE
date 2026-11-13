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
package io.karpfen.ide.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import io.karpfen.ide.app.crash.ErrorDialogPresenter
import io.karpfen.ide.app.crash.IDE_LOG_FILE_NAME
import io.karpfen.ide.app.launch.runtimeHostsClosedAtExit
import io.karpfen.ide.app.logging.LanguageToolsLogRouting
import io.karpfen.ide.app.logging.LogbackLanguageToolsLogRouting
import io.karpfen.ide.app.window.awtScreenAreas
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.plus
import kotlinx.coroutines.swing.Swing
import java.awt.EventQueue
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Composition root of the desktop application. The wiring is explicit plain Kotlin, without a dependency-injection
 * framework.
 *
 * [prepare] does all the wiring that needs no window, so it runs headless in tests with fake or test services. It
 * creates the `WorkbenchState` from [workbenchStateFactory] over [services]. It creates a `CommandRegistry` for
 * `WorkbenchServices.platform`, which is the one source of the platform, and registers the commands in it. Then it
 * calls `start()` and opens the initial folder. [run] calls [prepare] and then shows the Compose `application` with
 * the main window. The window title comes from `WorkbenchState.windowTitle`. The window bounds are read from and
 * written to `WorkbenchState.layout`. The menu bar comes from [IdeMenus] and `IdeMenuBar`. A close request goes
 * through `WorkbenchState.requestExit`, launched in the application's scope. Uncaught exceptions go through
 * [CrashHandling] into `dialogs.ask(DialogRequest.Error(…))`.
 *
 * The application owns one `RingOutputBuffer` for the output of the language tools. It hands the buffer to [services]
 * as the workbench's `OutputBuffer`. For the life of the prepared session, it routes what dsl-tools logs into that
 * buffer, as its `OutputSink`, through [languageToolsLog]. `logback.xml` keeps the same logging off the console.
 *
 * The default [services] get the one `RuntimeSessions()` of the process and the IDE's runtime hosts from
 * `runtimeHostsClosedAtExit()`, the Docker container of each workspace. When the window is closed and
 * `WorkbenchState.requestExit` allows it, [IdeRunState] shuts the runtime down before the application exits. When the
 * process ends in any other way that still runs its shutdown hooks, the hosts are closed there.
 *
 * The app's `main` resolves the home directory before it constructs this class. If
 * `SettingsLocations.HOME_ENVIRONMENT_VARIABLE` is set, its value is copied into the system property
 * `SettingsLocations.HOME_PROPERTY` before any logger exists. So the default [services] and `logback.xml` see it. The
 * default services come from `defaultWorkbenchServices`, which uses `SettingsLocations.defaultDirectory()` and
 * `HostPlatform.current()`. The settings directory and the platform are parameters of the services only.
 *
 * @param exitAfterMs when set, from `KARPFEN_IDE_EXIT_AFTER_MS`, the application exits with status 0 after that time.
 * @param services creates the workbench collaborators over the output of the language tools. [prepare] calls it once.
 *   Tests pass test services.
 * @param workbenchStateFactory creates the workbench state. Tests may pass a fake.
 * @param languageToolsLog routes what dsl-tools logs into the output of the language tools. By default it goes
 *   through the application's logger context. Tests may pass one of their own.
 */
public class IdeApplication(
    private val exitAfterMs: Long? = null,
    private val services: (OutputBuffer) -> WorkbenchServices = { output ->
        defaultWorkbenchServices(
            languageToolsOutput = output,
            runtimeSessions = RuntimeSessions(),
            runtimeHosts = runtimeHostsClosedAtExit(),
        )
    },
    private val workbenchStateFactory: (CoroutineScope, WorkbenchServices) -> WorkbenchState = ::WorkbenchState,
    private val languageToolsLog: LanguageToolsLogRouting = LogbackLanguageToolsLogRouting.ofApplication(),
) {
    /** What the language tools log while the application runs, shown in the workbench's Language Tools Output. */
    private val languageToolsOutput = RingOutputBuffer()

    /**
     * Wires the IDE without UI in [scope], the UI scope. It routes what dsl-tools logs into the output of the language
     * tools, creates the services and the workbench state, and registers the commands in a new
     * `CommandRegistry(services.platform)`. Then it starts the state and opens [initialFolder] if one is given.
     *
     * Opening joins the `openFolder` job, so the session is ready when this returns. The one exception is a flow that
     * first asks the user something, for example "Cannot open folder" for a folder that vanished. Only the window can
     * show that dialog, so this returns while the question is pending.
     *
     * The returned session ends the routing when it is closed. If anything fails, or the caller is cancelled, the
     * state is closed, the routing ends and the failure propagates.
     */
    public suspend fun prepare(scope: CoroutineScope, initialFolder: Path?): IdeSession {
        val languageToolsRoute = languageToolsLog.attach(languageToolsOutput)
        try {
            return prepareSession(scope, initialFolder, languageToolsRoute)
        } catch (e: Throwable) {
            languageToolsRoute.close()
            throw e
        }
    }

    /** The session of [prepare] over [languageToolsRoute]. Closes the state if anything fails after it was created. */
    private suspend fun prepareSession(scope: CoroutineScope, initialFolder: Path?, languageToolsRoute: AutoCloseable): IdeSession {
        val workbenchServices = services(languageToolsOutput)
        val state = workbenchStateFactory(scope, workbenchServices)
        try {
            val commands = CommandRegistry(workbenchServices.platform)
            state.registerCommands(commands)
            state.start()
            if (initialFolder != null) state.openFolder(initialFolder).joinUnlessAsking(state.dialogs)
            return IdeSession(state, commands, languageToolsRoute)
        } catch (e: Throwable) {
            state.close()
            throw e
        }
    }

    /**
     * Starts the UI and blocks until the application exits. Opens [initialFolder] if one is given.
     *
     * The application scope runs on the Swing event-dispatch thread. [CrashHandling] logs and shows the failures of
     * that scope, the uncaught exceptions of every thread and the failures inside the main window. The session is
     * closed on the event-dispatch thread when the application exits, because its workbench state is used from the UI
     * thread only.
     *
     * @throws IllegalStateException if the session could not be prepared. The cause is logged, and the application
     *   ends.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    public fun run(initialFolder: Path?) {
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val errorDialogs = ErrorDialogPresenter(uiScope, SettingsLocations.logDirectory().resolve(IDE_LOG_FILE_NAME))
        val crashHandling = CrashHandling { error -> errorDialogs.show(error) }
        crashHandling.install()
        val applicationScope = uiScope + CoroutineExceptionHandler { _, error -> crashHandling.handle(error) }
        val runState = IdeRunState(
            scope = applicationScope,
            prepare = { prepare(applicationScope, initialFolder) },
            onRunning = { session -> errorDialogs.attach { request -> session.state.dialogs.ask(request) } },
            exitAfter = exitAfterMs?.milliseconds,
        )
        val windowExceptions = WindowExceptionHandlerFactory { WindowExceptionHandler(crashHandling::handle) }
        val screens = awtScreenAreas()
        runState.start()
        try {
            application(exitProcessOnExit = false) { IdeApplicationContent(runState, windowExceptions, screens) }
        } finally {
            EventQueue.invokeAndWait(runState::dispose)
        }
        runState.startupFailure?.let { throw IllegalStateException("the IDE could not start", it) }
    }
}

/**
 * Joins this intent's job, or returns as soon as its flow waits for the answer to a dialog of [dialogs]. Before the
 * window exists, nobody could answer the dialog.
 */
private suspend fun Job.joinUnlessAsking(dialogs: DialogState) {
    merge(flow { join(); emit(Unit) }, dialogs.current.filterNotNull().map { }).first()
}

/**
 * The wired IDE of one application run: the workbench [state] and the [commands] the menus are built from. Closing the
 * session closes its state and then [languageToolsRoute], the routing of what dsl-tools logs into the state's output.
 * The routing is closed even when closing the state fails.
 */
public class IdeSession(
    public val state: WorkbenchState,
    public val commands: CommandRegistry,
    private val languageToolsRoute: AutoCloseable,
) : AutoCloseable {
    override fun close() {
        try {
            state.close()
        } finally {
            languageToolsRoute.close()
        }
    }
}
