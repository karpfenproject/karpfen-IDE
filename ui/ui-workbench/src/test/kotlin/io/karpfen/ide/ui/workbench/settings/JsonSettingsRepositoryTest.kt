package io.karpfen.ide.ui.workbench.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [JsonSettingsRepository]: defaults, round trips, unknown keys and values, corrupt-file recovery, I/O failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class JsonSettingsRepositoryTest {

    @TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-21T08:30:05Z"), ZoneOffset.UTC)
    private val file: Path get() = dir.resolve(SettingsLocations.SETTINGS_FILE_NAME)

    private fun kotlinx.coroutines.test.TestScope.repository(directory: Path = dir) =
        SettingsRepository(directory, StandardTestDispatcher(testScheduler), clock)

    private fun backups(): List<String> =
        Files.list(dir).use { files -> files.map { it.fileName.toString() }.filter { "corrupt" in it }.sorted().toList() }

    @Test
    fun `without a file the defaults load and nothing is written`() = runTest {
        val settings = repository()
        assertEquals(IdeSettings(), settings.settings.value)
        assertEquals(IdeSettings(), settings.load())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `updates are published, written as JSON and load again, tool windows by name incl unknown ones`() = runTest {
        val settings = repository(dir.resolve("new/home"))
        val updated = settings.update {
            it.withRecentFolder("/a").copy(
                theme = ThemeMode.DARK,
                editorFontSize = 15f,
                windowBounds = WindowBounds(10, 20, 800, 600, maximized = true),
                toolWindows = mapOf("PROJECT" to ToolWindowLayout(false, 300f), "RUNTIME" to ToolWindowLayout(size = 120f)),
            )
        }
        assertEquals(updated, settings.settings.value)
        val written = dir.resolve("new/home").resolve(SettingsLocations.SETTINGS_FILE_NAME)
        assertTrue(Json.parseToJsonElement(Files.readString(written)).jsonObject.containsKey("toolWindows"))

        val reloaded = repository(dir.resolve("new/home")).load()
        assertEquals(updated, reloaded)
        assertEquals(ToolWindowLayout(size = 120f), reloaded.toolWindows["RUNTIME"], "unknown tool windows are kept")
    }

    @Test
    fun `unknown keys are ignored and unknown enum values take the default`() = runTest {
        Files.writeString(file, """{ "recentFolders": ["/x"], "future": { "a": 1 }, "theme": "SEPIA", "editorFontSize": 14 }""")
        val loaded = repository().load()
        assertEquals(IdeSettings(recentFolders = listOf("/x"), editorFontSize = 14f), loaded)
        assertTrue(backups().isEmpty())
    }

    @Test
    fun `a corrupt file is backed up under a name from the clock and reset to the defaults`() = runTest {
        Files.writeString(file, "{ not json")
        assertEquals(IdeSettings(), repository().load())
        assertEquals(listOf("settings.json.corrupt-20261021-083005"), backups())
        assertEquals("{ not json", Files.readString(dir.resolve("settings.json.corrupt-20261021-083005")))
        assertEquals(IdeSettings(), Json.decodeFromString(IdeSettings.serializer(), Files.readString(file)), "the defaults are written")

        Files.writeString(file, NEGATIVE_FONT_SIZE)
        assertEquals(IdeSettings(), repository().load())
        Files.writeString(file, """{ "recentFolders": "not a list" }""")
        assertEquals(IdeSettings(), repository().load())
        assertEquals(
            listOf("", "-2", "-3").map { "settings.json.corrupt-20261021-083005$it" },
            backups(),
        )
    }

    @Test
    fun `a file that is not UTF-8 is corrupt too - backed up, then reset, never silently overwritten`() = runTest {
        val latin1 = LATIN1_SETTINGS
        Files.write(file, latin1)
        val settings = repository()

        assertEquals(IdeSettings(), settings.load())
        settings.update { it.withRecentFolder("/tmp/other") }

        assertEquals(listOf("settings.json.corrupt-20261021-083005"), backups(), "the undecodable file is backed up")
        assertContentEquals(latin1, Files.readAllBytes(dir.resolve("settings.json.corrupt-20261021-083005")))
        assertEquals(listOf("/tmp/other"), Json.decodeFromString(IdeSettings.serializer(), Files.readString(file)).recentFolders)
    }

    @Test
    fun `an existing file that cannot be read is left as it is - the session runs on the defaults`() = runTest {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "needs POSIX permissions")
        val original = """{"recentFolders": ["/Users/x/robots"], "editorFontSize": 18.0}"""
        Files.writeString(file, original)
        Files.setPosixFilePermissions(file, emptySet())
        try {
            assumeFalse(Files.isReadable(file), "the user can read any file (root)")
            val settings = repository()

            assertEquals(IdeSettings(), settings.load())
            val updated = settings.update { it.withRecentFolder("/tmp/other") }

            assertEquals(listOf("/tmp/other"), updated.recentFolders, "the update is published")
            assertEquals(updated, settings.settings.value)
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(original, Files.readString(file), "the user's settings are not overwritten")
        assertTrue(backups().isEmpty())
    }

    @Test
    fun `write failures are logged, not thrown, and the settings are still published`() = runTest {
        val blocked = Files.writeString(dir.resolve("blocked"), "a file where the directory should be")
        val settings = repository(blocked)
        val updated = settings.update { it.withRecentFolder("/a") }
        assertEquals(listOf("/a"), updated.recentFolders)
        assertEquals(updated, settings.settings.value)
        assertEquals(IdeSettings(), repository(blocked).load(), "unreadable: the defaults")
    }

    @Test
    fun `the default directory is the IDE home or the user's karpfen-ide directory`() {
        val previous = System.getProperty(SettingsLocations.HOME_PROPERTY)
        try {
            System.setProperty(SettingsLocations.HOME_PROPERTY, dir.toString())
            assertEquals(dir, SettingsLocations.defaultDirectory())
            assertEquals(dir.resolve("logs"), SettingsLocations.logDirectory())
            System.setProperty(SettingsLocations.HOME_PROPERTY, " ")
            assertEquals(".karpfen-ide", SettingsLocations.defaultDirectory().fileName.toString())
        } finally {
            if (previous == null) {
                System.clearProperty(SettingsLocations.HOME_PROPERTY)
            } else {
                System.setProperty(SettingsLocations.HOME_PROPERTY, previous)
            }
        }
    }

    private companion object {
        /** Valid JSON whose font size violates an [IdeSettings] invariant. */
        const val NEGATIVE_FONT_SIZE = """{ "editorFontSize": -1 }"""

        /** Settings hand-edited and saved as Latin-1: the é is the single byte 0xE9, which is no UTF-8. */
        val LATIN1_SETTINGS: ByteArray
            get() = """{"recentFolders": ["/Users/x/Café"], "editorFontSize": 18.0}""".toByteArray(Charsets.ISO_8859_1)
    }
}
