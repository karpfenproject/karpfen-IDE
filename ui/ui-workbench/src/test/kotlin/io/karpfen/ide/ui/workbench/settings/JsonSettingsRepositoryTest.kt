package io.karpfen.ide.ui.workbench.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
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

        Files.writeString(file, """{ "editorFontSize": -1 }""") // violates an IdeSettings invariant
        assertEquals(IdeSettings(), repository().load())
        Files.writeString(file, """{ "recentFolders": "not a list" }""")
        assertEquals(IdeSettings(), repository().load())
        assertEquals(
            listOf("", "-2", "-3").map { "settings.json.corrupt-20261021-083005$it" },
            backups(),
        )
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
}
