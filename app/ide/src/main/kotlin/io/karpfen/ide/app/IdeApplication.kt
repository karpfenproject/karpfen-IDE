package io.karpfen.ide.app

import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Composition root of the desktop application (spec §9, ADR 0009: explicit wiring, no DI framework).
 *
 * [prepare] does all wiring that needs no window — the `WorkbenchState` from [workbenchStateFactory] over [services],
 * a `CommandRegistry` with the registered commands, `start()` and the initial folder — so it runs headless in tests with
 * fake or test services. [run] calls it and then shows the Compose `application` with the main window (title from
 * `WorkbenchState.windowTitle`, bounds from and to `WorkbenchState.layout`, menu bar from [IdeMenus] and `IdeMenuBar`,
 * close request through `WorkbenchState.requestExit`, uncaught exceptions through [CrashHandling] into
 * `dialogs.ask(DialogRequest.Error(…))`).
 *
 * The app's `main` resolves the home directory first: `SettingsLocations.HOME_ENVIRONMENT_VARIABLE` (if set) is copied
 * into the system property `SettingsLocations.HOME_PROPERTY` before any logger exists, then [settingsDirectory] defaults
 * to `SettingsLocations.defaultDirectory()`.
 *
 * @param exitAfterMs when set (`KARPFEN_IDE_EXIT_AFTER_MS`), the application exits with status 0 after that time.
 * @param services creates the workbench collaborators (called once, by [prepare]); tests pass test services.
 * @param workbenchStateFactory creates the workbench state; tests may pass a fake.
 */
public class IdeApplication(
    private val settingsDirectory: Path = SettingsLocations.defaultDirectory(),
    private val platform: HostPlatform = HostPlatform.current(),
    private val exitAfterMs: Long? = null,
    private val services: () -> WorkbenchServices = { defaultWorkbenchServices(settingsDirectory, platform) },
    private val workbenchStateFactory: (CoroutineScope, WorkbenchServices) -> WorkbenchState = ::WorkbenchState,
) {
    /**
     * Wires the IDE without UI in [scope] (the UI scope): creates the services and the workbench state, registers the
     * commands in a new `CommandRegistry`, starts the state and opens [initialFolder] if given.
     */
    public suspend fun prepare(scope: CoroutineScope, initialFolder: Path?): IdeSession =
        TODO("WP-APP: wire the workbench state and the commands")

    /** Starts the UI (blocking until the application exits); opens [initialFolder] if given. */
    public fun run(initialFolder: Path?): Unit = TODO("WP-APP: run the application around prepare()")
}

/** The wired IDE of one application run: the workbench [state] and the [commands] the menus are built from. */
public class IdeSession(public val state: WorkbenchState, public val commands: CommandRegistry)
