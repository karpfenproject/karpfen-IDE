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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.time.Clock

/**
 * A [SettingsRepository] that stores `settings.json` with kotlinx.serialization. Writes are atomic, and a corrupt file
 * is backed up before it is replaced.
 *
 * [SettingsFile] does the file I/O. This class decodes the file, publishes the settings and serializes the updates. A
 * mutex runs the updates one at a time, so concurrent updates never lose each other's changes.
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

    /**
     * True while the existing file could not be read. It may hold settings that are unknown here, so it is not
     * replaced. Guarded by [writes].
     */
    private var keepUnreadableFile = false

    override val settings: StateFlow<IdeSettings>
        get() = current.asStateFlow()

    override suspend fun load(): IdeSettings = writes.withLock {
        val loaded = withContext(ioDispatcher) { read() }
        current.value = loaded
        loaded
    }

    override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings = writes.withLock {
        val updated = transform(current.value)
        current.value = updated
        withContext(ioDispatcher) { write(updated) }
        updated
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
            json.decodeFromString(IdeSettings.serializer(), text)
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
            file.write(json.encodeToString(IdeSettings.serializer(), settings))
        } catch (e: IOException) {
            logger.warn("Could not write the settings {}", file.path, e)
        }
    }

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
