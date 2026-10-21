package io.karpfen.ide.ui.workbench.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.time.Clock

/**
 * Loads and stores [IdeSettings] (Repository pattern, spec §8) as `settings.json` in an injectable directory. A
 * corrupt file (also one that is not UTF-8) is backed up (`settings.json.corrupt-<yyyyMMdd-HHmmss>`, the time taken
 * from the injected clock, with `-2`, `-3`, … appended if that name exists) and replaced by defaults; a file that exists
 * but cannot be read is left as it is (the defaults are used and not written over it). I/O errors are logged and never
 * thrown to the UI.
 */
public interface SettingsRepository {

    /** The current settings ([IdeSettings] defaults until [load] completes). */
    public val settings: StateFlow<IdeSettings>

    /** Reads the settings file (defaults if absent) and publishes it through [settings]. */
    public suspend fun load(): IdeSettings

    /** Applies [transform], publishes the result and writes it to disk; returns the new settings. */
    public suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings
}

/**
 * A JSON-file [SettingsRepository] in [directory] (created on first write); file I/O runs on [ioDispatcher]; [clock]
 * names corrupt-file backups (tests pass `Clock.fixed`).
 */
public fun SettingsRepository(
    directory: Path = SettingsLocations.defaultDirectory(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: Clock = Clock.systemDefaultZone(),
): SettingsRepository = JsonSettingsRepository(directory, ioDispatcher, clock)

/** Where the IDE keeps its per-user files (spec §8, §9). Pure functions over system properties. */
public object SettingsLocations {
    /** Name of the settings file. */
    public const val SETTINGS_FILE_NAME: String = "settings.json"

    /**
     * System property naming the IDE's home directory (settings and logs). The app's `logback.xml` reads the same
     * property (`${karpfen.ide.home:-${user.home}/.karpfen-ide}`), so settings and log move together.
     */
    public const val HOME_PROPERTY: String = "karpfen.ide.home"

    /**
     * Environment variable the app's `main` copies into [HOME_PROPERTY] before the first logger is created, so launch
     * smoke tests run with a temporary home instead of the owner's real one.
     */
    public const val HOME_ENVIRONMENT_VARIABLE: String = "KARPFEN_IDE_HOME"

    /** The directory named by [HOME_PROPERTY] if set (and not blank), else `~/.karpfen-ide`. */
    public fun defaultDirectory(): Path =
        System.getProperty(HOME_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".karpfen-ide")

    /** `<directory>/logs`, where `logback.xml` of the app writes `ide.log`. */
    public fun logDirectory(directory: Path = defaultDirectory()): Path = directory.resolve("logs")
}
