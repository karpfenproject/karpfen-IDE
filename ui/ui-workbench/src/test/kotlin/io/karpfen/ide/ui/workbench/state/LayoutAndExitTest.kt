package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.settings.ToolWindowLayout
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Layout persistence (decision 31) and the exit flow (spec §8, §9). */
class LayoutAndExitTest {

    @TempDir
    lateinit var dir: Path

    private val settingsDirectory: Path get() = dir.resolve("settings")
    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    @Test
    fun `start loads the settings and restores the layout`() = runTest(timeout = 60.seconds) {
        TestWorkbench.write(
            settingsDirectory.resolve(SettingsLocations.SETTINGS_FILE_NAME),
            """{ "theme": "DARK", "toolWindows": { "OUTLINE": { "visible": false, "size": 300.0 }, "RUNTIME": { "size": 99.0 } } }""",
        )
        withWorkbench(settingsDirectory) {
            state.start()
            assertEquals(ThemeMode.DARK, state.layout.state.value.theme)
            assertEquals(ToolWindowLayout(visible = false, size = 300f), state.layout.state.value.toolWindow(ToolWindowId.OUTLINE))
            assertEquals(ToolWindowId.PROJECT.defaultLayout, state.layout.state.value.toolWindow(ToolWindowId.PROJECT))
            settle()
            assertEquals(0, settings.updates, "restoring writes nothing")
        }
    }

    @Test
    fun `a burst of resizes writes the settings once, after the debounce`() = runTest(timeout = 60.seconds) {
        withWorkbench(settingsDirectory) {
            state.start()
            settle()
            (1..20).forEach {
                state.layout.resizeToolWindow(ToolWindowId.PROJECT, 200f + it)
                advanceTimeBy(20.milliseconds)
                runCurrent()
            }
            assertEquals(0, settings.updates, "still dragging")
            settle()
            assertEquals(1, settings.updates)
            assertEquals(ToolWindowLayout(size = 220f), state.settings.value.toolWindows[ToolWindowId.PROJECT.name])
            assertTrue(Files.readString(settingsDirectory.resolve(SettingsLocations.SETTINGS_FILE_NAME)).contains("220"))
        }
    }

    @Test
    fun `exit persists the layout and asks about unsaved changes`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        val machine = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kstates")
        withWorkbench(settingsDirectory) {
            state.start()
            open(root)
            openEditor(machine)
            document(machine).session.text.apply(listOf(TextEdit.insert(0, "// x\n")), EditOrigin.Typing)
            state.layout.toggleToolWindow(ToolWindowId.PROBLEMS)
            settle()
            val writes = settings.updates

            val cancelled = async { state.requestExit() }
            answer(DialogRequest.UnsavedChanges::class, DialogResponse.Cancel)
            assertFalse(cancelled.await())

            val saved = async { state.requestExit() }
            answer(DialogRequest.UnsavedChanges::class, DialogResponse.Save)
            assertTrue(saved.await())
            assertTrue(Files.readString(machine).startsWith("// x"))
            assertTrue(settings.updates > writes, "the layout is written on exit")
            assertFalse(state.settings.value.toolWindows.getValue(ToolWindowId.PROBLEMS.name).visible)
        }
    }

    @Test
    fun `exit waits for a running intent flow`() = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(settingsDirectory) {
            open(root)
            state.rename(root.resolve("karpfen-dsl-tools"))
            settle()
            val exit = async { state.requestExit() }
            settle()
            assertFalse(exit.isCompleted, "the rename dialog is still open")
            answer(DialogRequest.Rename::class, DialogResponse.Cancel)
            assertTrue(exit.await())
        }
    }
}
