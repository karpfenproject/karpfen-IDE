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

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * A [SettingsRepository] that stores the user's part as `settings.json` with kotlinx.serialization and the project
 * part as `.karpfenproject` in the project folder. Writes of `settings.json` are atomic, and a corrupt file is backed
 * up before it is replaced.
 *
 * [SettingsFile] does the file I/O of `settings.json`. This class decodes the file, publishes the settings and
 * serializes the updates. A mutex runs the updates one at a time, so concurrent updates never lose each other's
 * changes. An update writes only the part that changed. The project file is written in place and not through a
 * temporary file, because every file that appears in the project folder shows up in the project tree.
 *
 * A file that does not decode is corrupt. That covers text that is not UTF-8, invalid JSON, a value of the wrong type,
 * and a value that the [IdeSettings] invariants reject. A corrupt file is moved aside, as [SettingsRepository]
 * describes, and the defaults are written in its place. Unknown keys are ignored, and unknown enum values fall back to
 * the default, so files written by older and newer versions load.
 *
 * I/O errors are logged and never thrown. A file that exists but cannot be read is never written over. The defaults
 * are used until a later [load] reads it.
 */
internal class JsonSettingsRepository(
    directory: Path,
    private val ioDispatcher: CoroutineDispatcher,
    clock: Clock,
) : SettingsRepository {

    private val file = SettingsFile(directory, clock)
    private val current = MutableStateFlow(IdeSettings())
    private val writes = Mutex()

    /** The folder of the open project, or null. Guarded by [writes]. */
    private var projectRoot: Path? = null

    /**
     * True while the existing file could not be read. It may hold settings that are unknown here, so it is not
     * replaced. Guarded by [writes].
     */
    private var keepUnreadableFile = false

    override val settings: StateFlow<IdeSettings>
        get() = current.asStateFlow()

    override suspend fun load(): IdeSettings = writes.withLock {
        val loaded = withContext(ioDispatcher) { read() }
        val merged = loaded.withProject(current.value.project)
        current.value = merged
        merged
    }

    override suspend fun openProject(root: Path?): IdeSettings = writes.withLock {
        val project = withContext(ioDispatcher) { root?.let(::readProject) } ?: ProjectSettings()
        projectRoot = root
        val switched = current.value.withProject(project)
        current.value = switched
        switched
    }

    override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings = writes.withLock {
        val before = current.value
        val updated = transform(before)
        current.value = updated
        withContext(ioDispatcher) {
            if (updated.user != before.user) write(updated)
            if (updated.project != before.project) projectRoot?.let { writeProject(it, updated.project) }
        }
        updated
    }

    /** The project settings in the project file of [root], or the defaults if there is no file or it cannot be read. */
    private fun readProject(root: Path): ProjectSettings {
        val path = root.resolve(ProjectSettings.FILE_NAME)
        return try {
            if (Files.isRegularFile(path)) ProjectSettingsFile.parse(Files.readString(path)) else ProjectSettings()
        } catch (e: IOException) {
            logger.warn("Could not read the project settings {}; using the defaults", path, e)
            ProjectSettings()
        }
    }

    /** Writes [project] to the project file of [root]. Failures are logged. */
    private fun writeProject(root: Path, project: ProjectSettings) {
        val path = root.resolve(ProjectSettings.FILE_NAME)
        try {
            Files.writeString(path, ProjectSettingsFile.format(project))
        } catch (e: IOException) {
            logger.warn("Could not write the project settings {}", path, e)
        }
    }

    /**
     * Reads and decodes the file. Returns the defaults if the file does not exist. A file that was read but is not
     * UTF-8 is corrupt, just like invalid JSON. That happens, for example, when the file was edited by hand and saved
     * as Latin-1. Decoding failures are caught as `IllegalArgumentException`. That type covers both the
     * `SerializationException` of kotlinx.serialization and the [IdeSettings] invariants. A file that cannot be read
     * sets [keepUnreadableFile].
     */
    private fun read(): IdeSettings {
        keepUnreadableFile = false
        val text = try {
            file.read() ?: return IdeSettings()
        } catch (e: CharacterCodingException) {
            return recoverFromCorruptFile(e)
        } catch (e: IOException) {
            logger.warn("Could not read the settings {}; using the defaults and leaving the file as it is", file.path, e)
            keepUnreadableFile = true
            return IdeSettings()
        }
        return try {
            json.decodeFromString(UserSettings.serializer(), text).toSettings()
        } catch (e: IllegalArgumentException) {
            recoverFromCorruptFile(e)
        }
    }

    /** Moves the corrupt file aside, logs [cause], and writes and returns the defaults. */
    private fun recoverFromCorruptFile(cause: Exception): IdeSettings {
        try {
            val backup = file.backUpCorruptFile()
            logger.warn("The settings {} are corrupt; moved them to {} and reset them to the defaults", file.path, backup, cause)
        } catch (e: IOException) {
            logger.warn("The settings {} are corrupt and could not be backed up", file.path, e)
        }
        return IdeSettings().also(::write)
    }

    /** Writes [settings] unless an unreadable file is kept. Failures are logged. */
    private fun write(settings: IdeSettings) {
        if (keepUnreadableFile) {
            logger.debug("Not writing the settings over the unreadable {}", file.path)
            return
        }
        try {
            file.write(json.encodeToString(UserSettings.serializer(), settings.user))
        } catch (e: IOException) {
            logger.warn("Could not write the settings {}", file.path, e)
        }
    }

    /**
     * What `settings.json` holds: the part of the settings that belongs to the user and the machine. Unknown keys are
     * ignored, so a file from the time when it held everything still loads.
     */
    @Serializable
    private data class UserSettings(val recentFolders: List<String> = emptyList(), val windowBounds: WindowBounds? = null) {
        /** These settings with the defaults for the project part. The [IdeSettings] invariants are checked here. */
        fun toSettings(): IdeSettings = IdeSettings(recentFolders = recentFolders, windowBounds = windowBounds)
    }

    /** The part of these settings that goes to `settings.json`. */
    private val IdeSettings.user: UserSettings get() = UserSettings(recentFolders, windowBounds)

    private companion object {
        private val logger = LoggerFactory.getLogger(JsonSettingsRepository::class.java)

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}
