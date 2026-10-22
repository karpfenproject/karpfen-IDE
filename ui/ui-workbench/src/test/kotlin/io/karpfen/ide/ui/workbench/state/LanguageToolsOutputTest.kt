package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.UnseenOutputProblems
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * How the mediator binds the Language Tools Output: it reads the services' buffer, counts problems as seen while its
 * tool window is visible in the layout, and passes the unseen ones on to the status bar.
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

            languageToolsOutput.append(loggedAt, OutputLevel.WARNING, "io.karpfen.dsl.parser.KmetaFileParser", "syntax error")
            languageToolsOutput.append(loggedAt, OutputLevel.DEBUG, "io.karpfen.dsl.dsl.textual.KmetaDSLConverter", "Type Robot")
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
        TestWorkbench.write(
            dir.resolve("settings").resolve(SettingsLocations.SETTINGS_FILE_NAME),
            """{ "toolWindows": { "OUTPUT": { "visible": true, "size": 150.0 } } }""",
        )
        withWorkbench(dir.resolve("settings")) {
            state.start()
            settle()

            languageToolsOutput.append(loggedAt, OutputLevel.ERROR, "io.karpfen.dsl.X", "failed")
            settle()

            assertTrue(state.statusBar.state.value.unseenOutput.isEmpty)
            assertEquals(listOf("failed"), state.output.state.value.lines.map { it.message })
        }
    }
}
