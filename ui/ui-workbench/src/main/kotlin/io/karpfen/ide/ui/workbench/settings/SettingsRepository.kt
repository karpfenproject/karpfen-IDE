package io.karpfen.ide.ui.workbench.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Loads and stores [IdeSettings] (Repository pattern, spec §8) as `settings.json` in an injectable directory. A
 * corrupt file is backed up (`settings.json.corrupt-<timestamp>`) and replaced by defaults; I/O errors are logged and
 * never thrown to the UI.
 */
public interface SettingsRepository {

    /** The current settings ([IdeSettings] defaults until [load] completes). */
    public val settings: StateFlow<IdeSettings>

    /** Reads the settings file (defaults if absent) and publishes it through [settings]. */
    public suspend fun load(): IdeSettings

    /** Applies [transform], publishes the result and writes it to disk; returns the new settings. */
    public suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings
}

/** A JSON-file [SettingsRepository] in [directory] (created on first write); file I/O runs on [ioDispatcher]. */
public fun SettingsRepository(
    directory: Path = SettingsLocations.defaultDirectory(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): SettingsRepository = JsonSettingsRepository(directory, ioDispatcher)

/** Where the IDE keeps its per-user files (spec §8, §9). Pure functions over system properties. */
public object SettingsLocations {
    /** Name of the settings file. */
    public const val SETTINGS_FILE_NAME: String = "settings.json"

    /** `~/.karpfen-ide`. */
    public fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".karpfen-ide")

    /** `<directory>/logs`, where `logback.xml` of the app writes `ide.log`. */
    public fun logDirectory(directory: Path = defaultDirectory()): Path = directory.resolve("logs")
}
