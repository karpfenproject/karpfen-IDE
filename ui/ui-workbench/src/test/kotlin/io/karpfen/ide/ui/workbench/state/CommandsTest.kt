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

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.workbench.command.RuntimeCommandIds
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

/**
 * Tests `registerCommands`. It covers the workbench commands, the recent folders, the view toggles and the editor
 * commands. The editor commands carry the shortcuts of the single editor keymap. They act only on a focused editor,
 * except for find and go-to. The test simulates the find bar's query field taking the focus by consuming the editor's
 * focus request.
 */
class CommandsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val machine: Path get() = root.resolve("karpfen-runtime/single_robot_example/cleaning_robot.kstates")

    private fun scenario(block: suspend TestWorkbench.(CommandRegistry) -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        withWorkbench(dir.resolve("settings")) {
            val registry = CommandRegistry(services.platform)
            state.registerCommands(registry)
            settle()
            block(registry)
        }
    }

    private fun CommandRegistry.enabled(id: String): Boolean = assertNotNull(find(id), "no command $id").isEnabled.value

    @Test
    fun `the workbench commands are registered with titles and shortcuts`() = scenario { registry ->
        val ids = registry.commands.value.keys
        listOf(
            WorkbenchCommandIds.OPEN_FOLDER, WorkbenchCommandIds.SAVE, WorkbenchCommandIds.SAVE_ALL, WorkbenchCommandIds.CLOSE_TAB,
            WorkbenchCommandIds.THEME_LIGHT, WorkbenchCommandIds.THEME_DARK,
        ).forEach { assertTrue(it in ids, it) }
        ToolWindowId.entries.forEach { assertEquals(it.title, registry.find(WorkbenchCommandIds.toggleToolWindow(it))?.title) }
        assertEquals(KeyShortcut(KeyCode.S, primary = true), registry.find(WorkbenchCommandIds.SAVE)?.shortcut)
        assertFalse(registry.enabled(WorkbenchCommandIds.SAVE), "no tab")
        assertFalse(registry.enabled(WorkbenchCommandIds.CLOSE_TAB))
    }

    @Test
    fun `view commands drive the layout`() = scenario { registry ->
        assertTrue(registry.execute(WorkbenchCommandIds.toggleToolWindow(ToolWindowId.OUTLINE)))
        assertFalse(state.layout.state.value.toolWindow(ToolWindowId.OUTLINE).visible)
        registry.execute(WorkbenchCommandIds.THEME_DARK)
        assertEquals(ThemeMode.DARK, state.layout.state.value.theme)
        registry.execute(WorkbenchCommandIds.THEME_LIGHT)
        assertEquals(ThemeMode.LIGHT, state.layout.state.value.theme)
    }

    @Test
    fun `Run shows the Runtime tool window with three quarters of the workbench's height`() = scenario { registry ->
        open(root)
        state.layout.reportAvailableHeight(800f)
        assertFalse(state.layout.state.value.toolWindow(ToolWindowId.RUNTIME).visible)

        assertTrue(registry.execute(RuntimeCommandIds.RUN))

        val runtime = state.layout.state.value.toolWindow(ToolWindowId.RUNTIME)
        assertTrue(runtime.visible)
        assertEquals(600f, runtime.size)
    }

    @Test
    fun `the Language Tools Output command opens the hidden tool window`() = scenario { registry ->
        val toggle = assertNotNull(registry.find(WorkbenchCommandIds.toggleToolWindow(ToolWindowId.OUTPUT)))
        assertEquals("Language Tools Output", toggle.title)
        assertFalse(state.layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)

        assertTrue(registry.execute(toggle.id))

        assertTrue(state.layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
    }

    @Test
    fun `open recent commands follow the recent folders and open them`() = scenario { registry ->
        val other = Files.createDirectories(dir.resolve("other")).toAbsolutePath().normalize()
        open(root)
        open(other)
        assertEquals(other.toString(), registry.find(WorkbenchCommandIds.OPEN_RECENT_PREFIX + 0)?.title)
        assertEquals(root.toString(), registry.find(WorkbenchCommandIds.OPEN_RECENT_PREFIX + 1)?.title)
        assertNull(registry.find(WorkbenchCommandIds.OPEN_RECENT_PREFIX + 2))

        registry.execute(WorkbenchCommandIds.OPEN_RECENT_PREFIX + 1)
        settle()
        assertEquals(root, state.workspace.value?.root)
        assertEquals(root.toString(), registry.find(WorkbenchCommandIds.OPEN_RECENT_PREFIX + 0)?.title)
    }

    @Test
    fun `file commands follow the tabs and call the intents`() = scenario { registry ->
        open(root)
        openEditor(machine)
        assertTrue(registry.enabled(WorkbenchCommandIds.SAVE))
        assertFalse(registry.enabled(WorkbenchCommandIds.SAVE_ALL), "nothing dirty")
        document(machine).session.text.apply(listOf(TextEdit.insert(0, "// x\n")), EditOrigin.Typing)
        settle()
        assertTrue(registry.enabled(WorkbenchCommandIds.SAVE_ALL))

        registry.execute(WorkbenchCommandIds.SAVE_ALL)
        settle()
        assertTrue(Files.readString(machine).startsWith("// x"))

        registry.execute(WorkbenchCommandIds.CLOSE_TAB)
        settle()
        assertTrue(state.tabs.tabs.value.isEmpty())
        assertFalse(registry.enabled(WorkbenchCommandIds.SAVE))
    }

    @Test
    fun `editor commands carry the editor titles and keymap shortcuts`() = scenario { registry ->
        EditorActionTitles.titles.keys.filter { it != EditorActionIds.TYPE_TEXT }.forEach { id ->
            val command = assertNotNull(registry.find(id), id)
            assertEquals(EditorActionTitles.of(id), command.title)
            assertEquals(keymap.shortcutFor(id), command.shortcut)
            assertFalse(command.isEnabled.value, "$id without an editor")
        }
        assertNull(registry.find(EditorActionIds.TYPE_TEXT))
        assertEquals(HostPlatform.MAC_OS, registry.platform)
        assertEquals(KeyShortcut(KeyCode.M, ctrl = true), registry.find(EditorActionIds.COMPLETION)?.shortcut, "Complete Code is Ctrl+M")
    }

    @Test
    fun `editor commands other than find and go-to are disabled while the editor is not focused`() = scenario { registry ->
        open(root)
        val editor = openEditor(machine)
        editor.execute(EditorActionIds.DUPLICATE)
        settle()
        val edited = editor.session.text.snapshot.value.text

        assertFalse(registry.enabled(EditorActionIds.UNDO), "unfocused")
        assertFalse(registry.enabled(EditorActionIds.PASTE))
        assertFalse(registry.execute(EditorActionIds.UNDO))
        listOf(EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION).forEach {
            assertTrue(registry.enabled(it), "$it runs without focus")
        }

        editor.onFocusChanged(true)
        settle()
        assertTrue(registry.enabled(EditorActionIds.UNDO))
        assertTrue(registry.execute(EditorActionIds.UNDO))
        assertTrue(edited != editor.session.text.snapshot.value.text, "the command reached the active editor")

        editor.onFocusChanged(false)
        settle()
        editor.consumeFocusRequest()
        assertTrue(registry.execute(EditorActionIds.FIND))
        assertNotNull(editor.overlays.findBar.value, "find opens the find bar")
        assertTrue(editor.focusRequested.value, "and requests focus for the editor")
    }

    @Test
    fun `find next and previous keep the focus in the open find bar and focus the editor otherwise`() = scenario { registry ->
        open(root)
        val editor = openEditor(machine)
        assertTrue(registry.execute(EditorActionIds.FIND))
        editor.find.setQuery(FindQuery("STATE"))
        settle()
        editor.consumeFocusRequest()
        val matches = editor.find.state.value.matches
        assertTrue(matches.size > 2)

        assertTrue(registry.execute(EditorActionIds.FIND_NEXT))
        settle()
        assertFalse(editor.focusRequested.value, "Cmd+G typed into the find bar leaves the focus there")
        assertEquals(matches[1], editor.caret.primary.selection, "and selects the next match")
        assertTrue(registry.execute(EditorActionIds.FIND_PREVIOUS))
        settle()
        assertFalse(editor.focusRequested.value)
        assertEquals(matches[0], editor.caret.primary.selection)

        editor.overlays.closeFind()
        assertTrue(registry.execute(EditorActionIds.FIND_NEXT))
        settle()
        assertTrue(editor.focusRequested.value, "without the find bar, find next focuses the editor")
        assertEquals(matches[1], editor.caret.primary.selection)
    }

    @Test
    fun `no command runs while a dialog is shown, and the commands come back when it is answered`() = scenario { registry ->
        open(root)
        val editor = openEditor(machine)
        editor.onFocusChanged(false)
        settle()
        editor.consumeFocusRequest()
        val rename = state.rename(machine)
        settle()
        assertNotNull(state.dialogs.current.value, "the rename dialog is shown")

        val everyCommand = registry.commands.value.keys
        everyCommand.forEach { assertFalse(registry.enabled(it), "$it is disabled behind the dialog") }
        assertFalse(registry.execute(EditorActionIds.FIND), "Cmd+F typed into the dialog does not reach the editor")
        assertNull(editor.overlays.findBar.value)
        assertFalse(editor.focusRequested.value, "the editor behind the dialog does not take the focus")
        assertFalse(registry.execute(WorkbenchCommandIds.CLOSE_TAB), "Cmd+W typed into the dialog is not queued")

        state.dialogs.respond(DialogResponse.Cancel)
        settle()
        assertTrue(rename.isCompleted)
        assertEquals(listOf(machine), state.tabs.tabs.value.map { it.path }, "no Close Tab ran after the dialog")
        assertTrue(registry.enabled(WorkbenchCommandIds.CLOSE_TAB))
        assertTrue(registry.enabled(EditorActionIds.FIND))
        assertTrue(registry.enabled(WorkbenchCommandIds.OPEN_FOLDER))
    }
}
