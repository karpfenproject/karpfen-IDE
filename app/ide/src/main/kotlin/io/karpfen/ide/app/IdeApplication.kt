package io.karpfen.ide.app

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import java.nio.file.Path

/**
 * Composition root of the desktop application (spec §9, ADR 0009: explicit wiring, no DI framework): creates the
 * `CommandRegistry`, the `WorkbenchState` from `defaultWorkbenchServices(settingsDirectory)`, registers the commands,
 * and runs the Compose `application` with the main window (title from `WorkbenchState.windowTitle`, bounds persisted,
 * menu bar from [IdeMenus], close request through `WorkbenchState.requestExit`).
 *
 * @param exitAfterMs when set (`KARPFEN_IDE_EXIT_AFTER_MS`), the application exits with status 0 after that time.
 */
public class IdeApplication(
    private val settingsDirectory: Path = SettingsLocations.defaultDirectory(),
    private val platform: HostPlatform = HostPlatform.current(),
    private val exitAfterMs: Long? = null,
) {
    /** Starts the UI (blocking until the application exits); opens [initialFolder] if given. */
    public fun run(initialFolder: Path?): Unit = TODO("WP-APP: wire and run the application")
}
