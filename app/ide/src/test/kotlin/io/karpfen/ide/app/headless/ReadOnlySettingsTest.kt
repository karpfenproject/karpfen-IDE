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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettingsFile
import io.karpfen.ide.ui.workbench.settings.project
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Covers [ReadOnlySettings]: a project file gives its language features and nothing else, a missing project file
 * gives the defaults and is not created, the user's settings are never read, and updates stay in memory.
 */
class ReadOnlySettingsTest {

    @TempDir
    lateinit var root: Path

    private val file: Path get() = root.resolve(ProjectSettings.FILE_NAME)

    @Test
    fun `the language features come from the project file, everything else stays at its default`() = runTest {
        val written = ProjectSettings(
            languageFeatures = setOf(KstatesFeature.HISTORY, KstatesFeature.BREAKPOINTS),
            openFiles = listOf("lamp.kstates"),
            activeFile = "lamp.kstates",
            bridges = listOf("http.kbridge.json"),
            capture = true,
            editorFontSize = 20f,
        )
        Files.writeString(file, ProjectSettingsFile.format(written))
        val settings = ReadOnlySettings(StandardTestDispatcher(testScheduler))

        val opened = settings.openProject(root)

        assertEquals(ProjectSettings(languageFeatures = written.languageFeatures), opened.project)
        assertEquals(opened, settings.settings.value)
    }

    @Test
    fun `a folder without a project file has the defaults, and the file is not created`() = runTest {
        val settings = ReadOnlySettings(StandardTestDispatcher(testScheduler))

        val opened = settings.openProject(root)

        assertEquals(ProjectSettings(), opened.project)
        assertFalse(Files.exists(file))
    }

    @Test
    fun `loading gives the defaults instead of the user's settings`() = runTest {
        assertEquals(IdeSettings(), ReadOnlySettings(StandardTestDispatcher(testScheduler)).load())
    }

    @Test
    fun `an update changes the settings in memory and writes neither the project file nor anything else`() = runTest {
        val text = ProjectSettingsFile.format(ProjectSettings(languageFeatures = setOf(KstatesFeature.PARALLEL)))
        Files.writeString(file, text)
        val settings = ReadOnlySettings(StandardTestDispatcher(testScheduler))
        settings.openProject(root)

        val updated = settings.update { it.withRecentFolder(root.toString()).copy(captureRuns = true) }
        settings.awaitWrites()

        assertEquals(listOf(root.toString()), updated.recentFolders)
        assertEquals(true, updated.captureRuns)
        assertEquals(updated, settings.settings.value)
        assertEquals(text, Files.readString(file))
        assertEquals(listOf(file), Files.list(root).use { it.toList() })
    }
}
