package io.karpfen.ide.app.launch

import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The outcome of [applyHomeOverride]: the IDE home it set ([home], null if none), or why the variable was ignored
 * ([warning], logged by `main` once logging is configured).
 */
internal data class HomeOverride(val home: Path?, val warning: String? = null)

/**
 * Copies the environment variable `SettingsLocations.HOME_ENVIRONMENT_VARIABLE` (`KARPFEN_IDE_HOME`), if set and not
 * blank, as an absolute normalized path into the system property `SettingsLocations.HOME_PROPERTY`
 * (`karpfen.ide.home`) through [setProperty]. `main` calls it before the first logger exists, because both
 * `SettingsLocations.defaultDirectory()` (settings) and the app's `logback.xml` (log file) read the property; so a
 * launch smoke test with a temporary home never touches the owner's settings or log (decision 34 of the work-package
 * plan). Pure apart from [setProperty]; nothing is logged here.
 */
internal fun applyHomeOverride(
    environment: (String) -> String?,
    setProperty: (String, String) -> Unit,
): HomeOverride {
    val value = environment(SettingsLocations.HOME_ENVIRONMENT_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HomeOverride(home = null)
    val home = try {
        Path.of(value).toAbsolutePath().normalize()
    } catch (e: InvalidPathException) {
        return HomeOverride(
            home = null,
            warning = "Ignoring ${SettingsLocations.HOME_ENVIRONMENT_VARIABLE}='$value': ${e.reason}",
        )
    }
    setProperty(SettingsLocations.HOME_PROPERTY, home.toString())
    return HomeOverride(home)
}
