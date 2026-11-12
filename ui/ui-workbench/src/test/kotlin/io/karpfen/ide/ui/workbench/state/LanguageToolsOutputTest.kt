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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.UnseenOutputProblems
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

/**
 * Tests how the mediator binds the Language Tools Output. It reads the buffer of the services. It counts problems as
 * seen while the output's tool window is visible in the layout. And it passes the unseen problems on to the status bar.
 */
class LanguageToolsOutputTest {

    @TempDir
    lateinit var dir: Path

    private val loggedAt: Instant = Instant.parse("2026-10-22T10:15:30Z")

    @Test
    fun `problems logged while the output is hidden reach the status bar until the output is shown`() = runTest(timeout = 60.seconds) {
        withWorkbench(dir.resolve("settings")) {
            state.start()
            settle()

            languageToolsOutput.append(loggedAt, OutputLevel.WARNING, "io.karpfen.dsl.parser.KmetaFileParser", "syntax error", null)
            languageToolsOutput.append(loggedAt, OutputLevel.DEBUG, "io.karpfen.dsl.dsl.textual.KmetaDSLConverter", "Type Robot", null)
            settle()

            assertEquals(listOf("syntax error", "Type Robot"), state.output.state.value.lines.map { it.message })
            assertEquals(UnseenOutputProblems(warnings = 1), state.output.state.value.unseen)
            assertEquals(UnseenOutputProblems(warnings = 1), state.statusBar.state.value.unseenOutput)

            state.layout.showToolWindow(ToolWindowId.OUTPUT)
            settle()

            assertTrue(state.output.state.value.unseen.isEmpty)
            assertTrue(state.statusBar.state.value.unseenOutput.isEmpty)
        }
    }

    @Test
    fun `a tool window stored as visible counts the output as seen from the start`() = runTest(timeout = 60.seconds) {
        val folder = dir.resolve("stored").toAbsolutePath().normalize()
        TestWorkbench.write(folder.resolve(ProjectSettings.FILE_NAME), "pane.output = shown 150\n")
        withWorkbench(dir.resolve("settings")) {
            state.start()
            open(folder)
            settle()

            languageToolsOutput.append(loggedAt, OutputLevel.ERROR, "io.karpfen.dsl.X", "failed", null)
            settle()

            assertTrue(state.statusBar.state.value.unseenOutput.isEmpty)
            assertEquals(listOf("failed"), state.output.state.value.lines.map { it.message })
        }
    }
}
