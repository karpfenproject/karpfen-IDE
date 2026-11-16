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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Tests [JsonSettingsRepository]. They cover the defaults, round trips, unknown keys and values, recovery from a
 * corrupt file, and I/O failures.
 */
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
    fun `the user's part is written as JSON and loads again, and nothing of a project goes there`() = runTest {
        val settings = repository(dir.resolve("new/home"))
        val bounds = WindowBounds(10, 20, 800, 600, maximized = true)
        val runtimeBounds = WindowBounds(900, 30, 960, 600)
        val updated = settings.update {
            it.withRecentFolder("/a").copy(theme = ThemeMode.DARK, editorFontSize = 15f, windowBounds = bounds, runtimeWindowBounds = runtimeBounds)
        }
        assertEquals(updated, settings.settings.value)
        val written = dir.resolve("new/home").resolve(SettingsLocations.SETTINGS_FILE_NAME)
        assertEquals(
            setOf("recentFolders", "windowBounds", "runtimeWindowBounds"),
            Json.parseToJsonElement(Files.readString(written)).jsonObject.keys,
        )

        val reloaded = repository(dir.resolve("new/home")).load()
        assertEquals(
            IdeSettings(recentFolders = listOf("/a"), windowBounds = bounds, runtimeWindowBounds = runtimeBounds),
            reloaded,
            "the theme belonged to no project",
        )
    }

    @Test
    fun `unknown keys are ignored, also those of the time when the file held everything`() = runTest {
        Files.writeString(file, """{ "recentFolders": ["/x"], "future": { "a": 1 }, "theme": "SEPIA", "editorFontSize": 14 }""")
        val loaded = repository().load()
        assertEquals(IdeSettings(recentFolders = listOf("/x")), loaded)
        assertTrue(backups().isEmpty())
    }

    @Test
    fun `the project part is stored in the project folder and comes back with the project`() = runTest {
        val lamp = Files.createDirectories(dir.resolve("lamp"))
        val other = Files.createDirectories(dir.resolve("other"))
        val projectFile = lamp.resolve(ProjectSettings.FILE_NAME)
        val settings = repository()
        settings.update { it.withRecentFolder("/a") }

        assertEquals(IdeSettings(recentFolders = listOf("/a")), settings.openProject(lamp))
        assertFalse(Files.exists(projectFile), "opening a folder writes nothing")

        val changed = settings.update {
            it.copy(
                theme = ThemeMode.DARK,
                uiScale = 1.2f,
                toolWindows = mapOf("RUNTIME" to ToolWindowLayout(visible = true, size = 400f)),
                openFiles = listOf("lamp.kstates"),
                activeFile = "lamp.kstates",
            )
        }
        assertEquals(changed.project, ProjectSettingsFile.parse(Files.readString(projectFile)))
        assertTrue("theme = dark" in Files.readString(projectFile))

        assertEquals(IdeSettings(recentFolders = listOf("/a")), settings.openProject(other), "another project has its own settings")
        assertEquals(changed, settings.openProject(lamp))
        assertEquals(changed, repository().also { it.load() }.openProject(lamp), "also after a restart")
    }

    @Test
    fun `without a project the project part is kept in memory only`() = runTest {
        val lamp = Files.createDirectories(dir.resolve("lamp"))
        val settings = repository()
        settings.openProject(lamp)
        settings.update { it.copy(theme = ThemeMode.DARK) }

        assertEquals(ThemeMode.SYSTEM, settings.openProject(null).theme, "the defaults apply again")
        settings.update { it.copy(uiScale = 1.5f) }

        assertEquals(1.5f, settings.settings.value.uiScale)
        assertFalse(Files.exists(file), "the user's file holds no project values")
        assertEquals(ProjectSettings(theme = ThemeMode.DARK), ProjectSettingsFile.parse(Files.readString(lamp.resolve(ProjectSettings.FILE_NAME))))
    }

    @Test
    fun `changing only the user's part leaves the project file alone, and the other way round`() = runTest {
        val lamp = Files.createDirectories(dir.resolve("lamp"))
        val settings = repository()
        settings.openProject(lamp)

        settings.update { it.withRecentFolder("/a") }
        assertFalse(Files.exists(lamp.resolve(ProjectSettings.FILE_NAME)))

        Files.delete(file)
        settings.update { it.copy(editorFontSize = 16f) }
        assertFalse(Files.exists(file))
        assertTrue(Files.exists(lamp.resolve(ProjectSettings.FILE_NAME)))
    }

    @Test
    fun `a corrupt file is backed up under a name from the clock and reset to the defaults`() = runTest {
        Files.writeString(file, "{ not json")
        assertEquals(IdeSettings(), repository().load())
        assertEquals(listOf("settings.json.corrupt-20261021-083005"), backups())
        assertEquals("{ not json", Files.readString(dir.resolve("settings.json.corrupt-20261021-083005")))
        assertEquals(emptyList(), recentFoldersIn(file), "the defaults are written")

        Files.writeString(file, TOO_MANY_RECENT_FOLDERS)
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
        assertEquals(listOf("/tmp/other"), recentFoldersIn(file))
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

    /** The recent folders that the JSON file [path] holds. */
    private fun recentFoldersIn(path: Path): List<String> =
        Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("recentFolders").jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        /** Valid JSON whose recent folders violate an [IdeSettings] invariant, because there are more than ten. */
        val TOO_MANY_RECENT_FOLDERS: String = (1..IdeSettings.MAX_RECENT_FOLDERS + 1).joinToString(prefix = "{ \"recentFolders\": [", postfix = "] }") { "\"/$it\"" }

        /** Settings edited by hand and saved as Latin-1. The é is the single byte 0xE9, which is not valid UTF-8. */
        val LATIN1_SETTINGS: ByteArray
            get() = """{"recentFolders": ["/Users/x/Café"], "editorFontSize": 18.0}""".toByteArray(Charsets.ISO_8859_1)
    }
}
