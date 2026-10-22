package io.karpfen.ide.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import io.karpfen.ide.app.crash.ErrorDialogPresenter
import io.karpfen.ide.app.crash.IDE_LOG_FILE_NAME
import io.karpfen.ide.app.window.awtScreenAreas
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import io.karpfen.ide.ui.workbench.dialog.DialogState
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
 * Composition root of the desktop application: explicit wiring in plain Kotlin, no dependency-injection framework.
 *
 * [prepare] does all wiring that needs no window — the `WorkbenchState` from [workbenchStateFactory] over [services], a
 * `CommandRegistry` for `WorkbenchServices.platform` (the one source of the platform) with the registered commands,
 * `start()` and the initial folder — so it runs headless in tests with fake or test services. [run] calls it and then
 * shows the Compose `application` with the main window (title from `WorkbenchState.windowTitle`, bounds from and to
 * `WorkbenchState.layout`, menu bar from [IdeMenus] and `IdeMenuBar`, close request through
 * `WorkbenchState.requestExit` launched in the application's scope, uncaught exceptions through [CrashHandling] into
 * `dialogs.ask(DialogRequest.Error(…))`).
 *
 * The app's `main` resolves the home directory before constructing this class:
 * `SettingsLocations.HOME_ENVIRONMENT_VARIABLE` (if set) is copied into the system property
 * `SettingsLocations.HOME_PROPERTY` before any logger exists, so the default [services] (`defaultWorkbenchServices()`:
 * `SettingsLocations.defaultDirectory()`, `HostPlatform.current()`) and `logback.xml` see it. Settings directory and
 * platform are parameters of the services only.
 *
 * @param exitAfterMs when set (`KARPFEN_IDE_EXIT_AFTER_MS`), the application exits with status 0 after that time.
 * @param services creates the workbench collaborators (called once, by [prepare]); tests pass test services.
 * @param workbenchStateFactory creates the workbench state; tests may pass a fake.
 */
public class IdeApplication(
    private val exitAfterMs: Long? = null,
    private val services: () -> WorkbenchServices = { defaultWorkbenchServices() },
    private val workbenchStateFactory: (CoroutineScope, WorkbenchServices) -> WorkbenchState = ::WorkbenchState,
) {
    /**
     * Wires the IDE without UI in [scope] (the UI scope): creates the services and the workbench state, registers the
     * commands in a new `CommandRegistry(services.platform)`, starts the state and opens [initialFolder] if given
     * (joining the `openFolder` job, so the session is ready when this returns — unless the flow asks the user first,
     * e.g. "Cannot open folder" for a folder that vanished: only the window can show that dialog, so this returns while
     * it is pending). If anything fails (or the caller is cancelled), the state is closed and the failure propagates.
     */
    public suspend fun prepare(scope: CoroutineScope, initialFolder: Path?): IdeSession {
        val workbenchServices = services()
        val state = workbenchStateFactory(scope, workbenchServices)
        try {
            val commands = CommandRegistry(workbenchServices.platform)
            state.registerCommands(commands)
            state.start()
            if (initialFolder != null) state.openFolder(initialFolder).joinUnlessAsking(state.dialogs)
            return IdeSession(state, commands)
        } catch (e: Throwable) {
            state.close()
            throw e
        }
    }

    /**
     * Starts the UI (blocking until the application exits); opens [initialFolder] if given.
     *
     * The application scope runs on the Swing event-dispatch thread; its failures, uncaught exceptions of every thread
     * and failures inside the main window are logged and shown by [CrashHandling]. The workbench state is closed when
     * the application exits, on the event-dispatch thread, because it is used from the UI thread only.
     *
     * @throws IllegalStateException if the session could not be prepared (the cause is logged, the application ends).
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
 * Joins this intent's job, or returns once its flow waits for an answer to a dialog of [dialogs]: before the window
 * exists, nobody could answer it.
 */
private suspend fun Job.joinUnlessAsking(dialogs: DialogState) {
    merge(flow { join(); emit(Unit) }, dialogs.current.filterNotNull().map { }).first()
}

/** The wired IDE of one application run: the workbench [state] and the [commands] the menus are built from. */
public class IdeSession(public val state: WorkbenchState, public val commands: CommandRegistry)
