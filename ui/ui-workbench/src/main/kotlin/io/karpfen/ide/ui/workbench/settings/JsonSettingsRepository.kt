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
import java.nio.file.Path
import java.time.Clock

/**
 * [SettingsRepository] storing `settings.json` with kotlinx.serialization (atomic write, corrupt-file backup).
 *
 * The file I/O is [SettingsFile]'s; this class decodes, publishes and serializes the updates (one at a time, so
 * concurrent updates never lose each other's changes). A file that does not decode — invalid JSON, a wrong value type,
 * a value the [IdeSettings] invariants reject — is corrupt: it is moved aside (see [SettingsRepository]) and the
 * defaults are written in its place. Unknown keys are ignored and unknown enum values fall back to the default, so
 * older and newer files load. I/O errors are logged, never thrown.
 */
internal class JsonSettingsRepository(
    directory: Path,
    private val ioDispatcher: CoroutineDispatcher,
    clock: Clock,
) : SettingsRepository {

    private val file = SettingsFile(directory, clock)
    private val current = MutableStateFlow(IdeSettings())
    private val writes = Mutex()

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

    private fun read(): IdeSettings {
        val text = try {
            file.read() ?: return IdeSettings()
        } catch (e: IOException) {
            logger.warn("Could not read the settings {}; using the defaults", file.path, e)
            return IdeSettings()
        }
        return try {
            json.decodeFromString(IdeSettings.serializer(), text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, like the IdeSettings invariants.
            recoverFromCorruptFile(e)
        }
    }

    private fun recoverFromCorruptFile(cause: Exception): IdeSettings {
        try {
            val backup = file.backUpCorruptFile()
            logger.warn("The settings {} are corrupt; moved them to {} and reset them to the defaults", file.path, backup, cause)
        } catch (e: IOException) {
            logger.warn("The settings {} are corrupt and could not be backed up", file.path, e)
        }
        return IdeSettings().also(::write)
    }

    private fun write(settings: IdeSettings) {
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
