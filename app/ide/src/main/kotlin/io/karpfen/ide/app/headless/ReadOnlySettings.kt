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

import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettingsFile
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.settings.withProject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The settings of the headless mode. They read what belongs to the project as such and write nothing.
 *
 * The optional language features and how the runtime is reached are properties of the project. [openProject] reads
 * them from the project file `.karpfenproject` of the folder, as `ProjectSettingsFile` parses it. Everything else that
 * the window remembers there, such as the bridges of the last run, the capture box, the open files and the layout, is
 * left at its default, and so are the user's own settings with the recent folders and the window bounds, which are
 * never read. [update] changes the settings in memory only. Neither the project file nor the user's settings file is
 * ever written, and a project file that does not exist is not created.
 *
 * A project file that cannot be read gives the defaults and a warning in the log.
 *
 * @param ioDispatcher where the project file is read.
 */
internal class ReadOnlySettings(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : SettingsRepository {

    private val current = MutableStateFlow(IdeSettings())

    override val settings: StateFlow<IdeSettings>
        get() = current.asStateFlow()

    /** Returns the settings as they are. The user's settings file is not read. */
    override suspend fun load(): IdeSettings = current.value

    override suspend fun openProject(root: Path?): IdeSettings {
        val stored = root?.let { withContext(ioDispatcher) { read(it) } } ?: ProjectSettings()
        val project = ProjectSettings(runtime = stored.runtime, languageFeatures = stored.languageFeatures)
        return current.value.withProject(project).also { current.value = it }
    }

    /** Applies [transform] in memory and writes nothing. */
    override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings =
        transform(current.value).also { current.value = it }

    /** Returns at once, because nothing is ever written. */
    override suspend fun awaitWrites() = Unit

    /** The settings in the project file of [root], or the defaults if there is none or it cannot be read. */
    private fun read(root: Path): ProjectSettings {
        val file = root.resolve(ProjectSettings.FILE_NAME)
        return try {
            if (Files.isRegularFile(file)) ProjectSettingsFile.parse(Files.readString(file)) else ProjectSettings()
        } catch (e: IOException) {
            log.warn("Could not read the project settings {}; using the defaults", file, e)
            ProjectSettings()
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ReadOnlySettings::class.java)
    }
}
