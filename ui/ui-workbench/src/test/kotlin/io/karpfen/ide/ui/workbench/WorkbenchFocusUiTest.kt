package io.karpfen.ide.ui.workbench

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.popup.EditorPopupTags
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.widgets.TabStripTestTags
import io.karpfen.ide.ui.foundation.widgets.TreeViewTestTags
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.view.WorkbenchDialogTestTags
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.tabs.view.EditorAreaTestTags
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keyboard focus of the composed workbench: modal dialogs keep the focus (Tab cycles inside them, no key reaches the
 * workbench behind them) and give it back when they are answered; the find bar keeps it on Find Next.
 */
@OptIn(ExperimentalTestApi::class)
class WorkbenchFocusUiTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val machine: Path get() = root.resolve(DescriptorExamples.CONTEXT_DIRECTORY).resolve("cleaning_robot.kstates")

    private val inDialog: SemanticsMatcher =
        hasTestTag(WorkbenchDialogTestTags.DIALOG) or hasAnyAncestor(hasTestTag(WorkbenchDialogTestTags.DIALOG))

    /** Opens the examples and [machine] from the project tree, focuses its editor and types [typed]. */
    private suspend fun WorkbenchUi.editMachine(typed: String): CodeEditorState {
        Examples.copyTo(root)
        workbench.open(root)
        settle()
        workbench.state.projectTree.reveal(machine)
        settle()
        treeRow(machine).performMouseInput { doubleClick() }
        settle()
        tagged(EditorAreaTestTags.EDITOR).requestFocus()
        tagged(EditorAreaTestTags.EDITOR).performTextInput(typed)
        settle()
        return assertNotNull(workbench.state.tabs.editorFor(machine))
    }

    private fun WorkbenchUi.text(): String = workbench.document(machine).session.text.snapshot.value.text

    private fun WorkbenchUi.focused(): SemanticsNodeInteraction = test.onNode(isFocused())

    private fun WorkbenchUi.press(key: Key) {
        focused().performKeyInput { pressKey(key) }
        settle()
    }

    @Test
    fun `Tab and Shift+Tab cycle inside a modal dialog, and no key reaches the editor behind it`() = workbenchUiTest(dir) {
        val editor = editMachine("x")
        tagged(TabStripTestTags.close(machine.toString())).performClick()
        settle()
        assertIs<DialogRequest.UnsavedChanges>(workbench.state.dialogs.current.value)
        test.onNode(isFocused() and inDialog).assertExists()

        repeat(DIALOG_FOCUS_STOPS * 2 + 1) { step ->
            press(Key.Tab)
            test.onNode(isFocused() and inDialog).assertExists("Tab $step left the dialog")
        }
        repeat(DIALOG_FOCUS_STOPS * 2 + 1) { step ->
            focused().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
            settle()
            test.onNode(isFocused() and inDialog).assertExists("Shift+Tab $step left the dialog")
        }
        val before = text()
        press(Key.Backspace)
        press(Key.Delete)

        assertEquals(before, text(), "keys typed while the dialog is shown never edit the document behind it")
        assertTrue(before.startsWith("xSTATEMACHINE"))
        assertFalse(editor.isFocused.value)
        assertIs<DialogRequest.UnsavedChanges>(workbench.state.dialogs.current.value)
    }

    @Test
    fun `the editor has the focus again after a dialog it gave the focus to is answered`() = workbenchUiTest(dir) {
        val editor = editMachine("x")
        assertTrue(editor.isFocused.value)

        workbench.state.closeActiveTab() // what Cmd+W runs
        settle()
        test.onNode(isFocused() and inDialog).assertExists()
        assertFalse(editor.isFocused.value)
        press(Key.Escape)

        assertNull(workbench.state.dialogs.current.value)
        assertTrue(editor.isFocused.value, "the focus returned to the editor, so the next keystroke is not lost")
        tagged(EditorAreaTestTags.EDITOR).performKeyInput { pressKey(Key.Backspace) }
        settle()
        assertTrue(text().startsWith("STATEMACHINE"), "Backspace after the typed x reached the editor: ${text().take(20)}")
    }

    @Test
    fun `the project tree has the focus again after the rename dialog it opened is cancelled`() = workbenchUiTest(dir) {
        editMachine("")
        val tree = test.onNode(hasTestTag(TreeViewTestTags.TREE) and hasAnyAncestor(hasTestTag(WorkbenchTestTags.toolWindow(ToolWindowId.PROJECT))))
        tree.requestFocus()
        settle()
        tree.assertIsFocused()

        workbench.state.rename(machine)
        settle()
        tagged(WorkbenchDialogTestTags.INPUT).assertIsFocused()
        press(Key.Tab)
        press(Key.Tab)
        press(Key.Tab)
        tagged(WorkbenchDialogTestTags.INPUT).assertIsFocused() // field → Rename → Cancel → field
        press(Key.Escape)

        assertNull(workbench.state.dialogs.current.value)
        tree.assertIsFocused()
    }

    @Test
    fun `Find Next run from the menu while the find bar has the focus keeps it there`() = workbenchUiTest(dir) {
        val editor = editMachine("")
        val registry = CommandRegistry(workbench.services.platform)
        workbench.state.registerCommands(registry)
        settle()

        assertTrue(registry.execute(EditorActionIds.FIND))
        settle()
        tagged(EditorPopupTags.FIND_QUERY).assertIsFocused()
        tagged(EditorPopupTags.FIND_QUERY).performTextInput("STATE")
        settle()
        val before = text()

        assertTrue(registry.execute(EditorActionIds.FIND_NEXT)) // Cmd+G typed into the query field reaches the menu
        settle()

        tagged(EditorPopupTags.FIND_QUERY).assertIsFocused()
        assertFalse(editor.isFocused.value)
        tagged(EditorPopupTags.FIND_QUERY).performTextInput("MACHINE")
        settle()
        assertEquals(before, text(), "refining the query does not type into the document")
        assertEquals("STATEMACHINE", editor.find.query.value.text)
    }

    private companion object {
        /** The focus stops of the unsaved-changes dialog: the dialog itself and its three buttons. */
        const val DIALOG_FOCUS_STOPS = 4
    }
}
