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
package io.karpfen.ide.ui.workbench.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.time.Clock

/**
 * Loads and stores [IdeSettings]. It follows the Repository pattern and keeps the two parts of the settings in two
 * places.
 *
 * The recent folders and the window bounds go to `settings.json` in a directory that the caller chooses, the IDE's
 * home. A corrupt file is backed up and replaced by the defaults. A file that is not UTF-8 also counts as corrupt.
 * The backup is named `settings.json.corrupt-<yyyyMMdd-HHmmss>`, with the time taken from the injected clock. If that
 * name exists, `-2`, `-3` and so on are appended. A file that exists but cannot be read is left as it is. The
 * defaults are used then, and they are not written over the file.
 *
 * The project part goes to the file `.karpfenproject` in the folder that [openProject] names, in the plain text format
 * of [ProjectSettingsFile]. The file is written when the project part changes, so a folder that is only looked at
 * gets no file.
 *
 * I/O errors are logged and never thrown to the UI.
 */
public interface SettingsRepository {

    /** The current settings. They are the [IdeSettings] defaults until [load] completes. */
    public val settings: StateFlow<IdeSettings>

    /**
     * Reads the settings of the user and publishes the result through [settings]. Uses the defaults if the file is
     * absent. The project part is left as it is.
     */
    public suspend fun load(): IdeSettings

    /**
     * Makes [root] the open project, or no folder for null. The project part of [settings] becomes what the project
     * file of [root] holds, or the defaults if there is no file or no project. From then on the project part is stored
     * in that file. Returns the new settings.
     */
    public suspend fun openProject(root: Path?): IdeSettings

    /**
     * Applies [transform], publishes the result and returns the new settings. Each of the two parts is written to its
     * place if it changed.
     */
    public suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings

    /**
     * Returns once every update that was started so far has reached the disk. [settings] publishes a change before
     * its file is written, so a caller that wants to see the file waits here first.
     */
    public suspend fun awaitWrites()
}

/**
 * Creates a [SettingsRepository] that stores the user's settings as a JSON file in [directory]. The directory is created
 * on the first write.
 * File I/O runs on [ioDispatcher]. [clock] names the backups of corrupt files. Tests pass `Clock.fixed` there.
 */
public fun SettingsRepository(
    directory: Path = SettingsLocations.defaultDirectory(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: Clock = Clock.systemDefaultZone(),
): SettingsRepository = JsonSettingsRepository(directory, ioDispatcher, clock)

/**
 * Where the IDE keeps its per-user files, which are the settings and the logs. Pure functions over system properties.
 */
public object SettingsLocations {
    /** The name of the settings file. */
    public const val SETTINGS_FILE_NAME: String = "settings.json"

    /**
     * The system property that names the IDE's home directory, which holds the settings and the logs. The app's
     * `logback.xml` reads the same property as `${karpfen.ide.home:-${user.home}/.karpfen-ide}`, so the settings and
     * the log always move together.
     */
    public const val HOME_PROPERTY: String = "karpfen.ide.home"

    /**
     * The environment variable that the app's `main` copies into [HOME_PROPERTY] before the first logger is created.
     * Launch smoke tests use it to run with a temporary home instead of the user's real one.
     */
    public const val HOME_ENVIRONMENT_VARIABLE: String = "KARPFEN_IDE_HOME"

    /** The directory named by [HOME_PROPERTY] if it is set and not blank, and `~/.karpfen-ide` otherwise. */
    public fun defaultDirectory(): Path =
        System.getProperty(HOME_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".karpfen-ide")

    /** The directory `<directory>/logs`, where the app's `logback.xml` writes `ide.log`. */
    public fun logDirectory(directory: Path = defaultDirectory()): Path = directory.resolve("logs")
}
