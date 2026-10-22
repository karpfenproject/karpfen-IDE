package io.karpfen.ide.ui.workbench

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.ui.foundation.widgets.StatusBarTestTags
import io.karpfen.ide.ui.foundation.widgets.TabStripTestTags
import io.karpfen.ide.ui.foundation.widgets.TreeViewTestTags
import io.karpfen.ide.ui.workbench.dialog.view.WorkbenchDialogTestTags
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.problems.view.ProblemKey
import io.karpfen.ide.ui.workbench.problems.view.ProblemsViewTestTags
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.tabs.view.EditorAreaTestTags
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.welcome.view.WelcomeTestTags
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless UI tests of the composed workbench over a temp-dir copy of the examples: empty state, layout, tree, editing
 * with the dirty marker and saving, closing a dirty tab, Problems and Outline navigation, the status bar and the
 * external-change banner.
 */
@OptIn(ExperimentalTestApi::class)
class WorkbenchUiTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve(DescriptorExamples.CONTEXT_DIRECTORY)
    private val machine: Path get() = robot.resolve("cleaning_robot.kstates")

    private suspend fun WorkbenchUi.openExamples() {
        Examples.copyTo(root)
        workbench.open(root)
        settle()
    }

    /** Opens [path] from the project tree by double-click. */
    private fun WorkbenchUi.openFromTree(path: Path) {
        workbench.state.projectTree.reveal(path)
        settle()
        treeRow(path).performMouseInput { doubleClick() }
        settle()
    }

    @Test
    fun `the empty state opens a recent folder and Open Folder asks the chooser`() = workbenchUiTest(dir) {
        Examples.copyTo(root)
        workbench.settings.update { IdeSettings().withRecentFolder(root.toString()) }
        settle()
        tagged(WelcomeTestTags.VIEW).assertIsDisplayed()

        tagged(WelcomeTestTags.OPEN_FOLDER).performClick()
        settle()
        assertEquals(1, workbench.folderChooser.calls.size)

        tagged(WelcomeTestTags.recentFolder(root.toString())).performClick()
        settle()
        assertEquals(root, workbench.state.workspace.value?.root)
        tagged(WorkbenchTestTags.toolWindow(ToolWindowId.PROJECT)).assertIsDisplayed()
        test.onNodeWithTag(WelcomeTestTags.VIEW).assertDoesNotExist()
    }

    @Test
    fun `the layout shows the tool windows and hides a toggled one`() = workbenchUiTest(dir) {
        openExamples()
        ToolWindowId.entries.forEach { tagged(WorkbenchTestTags.toolWindow(it)).assertIsDisplayed() }
        tagged(EditorAreaTestTags.AREA).assertIsDisplayed()
        tagged(StatusBarTestTags.BAR).assertIsDisplayed()
        treeRow(root).assertIsDisplayed()

        workbench.state.layout.toggleToolWindow(ToolWindowId.PROBLEMS)
        settle()
        test.onNodeWithTag(WorkbenchTestTags.toolWindow(ToolWindowId.PROBLEMS)).assertDoesNotExist()
        tagged(WorkbenchTestTags.toolWindow(ToolWindowId.OUTLINE)).assertIsDisplayed()
    }

    @Test
    fun `a file opened from the tree is edited, marked dirty and saved`() = workbenchUiTest(dir) {
        openExamples()
        openFromTree(machine)
        val tabId = machine.toString()
        tagged(TabStripTestTags.tab(tabId)).assertIsDisplayed()
        tagged(EditorAreaTestTags.EDITOR).assertIsDisplayed()
        test.onNodeWithTag(TabStripTestTags.dirty(tabId), useUnmergedTree = true).assertDoesNotExist()

        tagged(EditorAreaTestTags.EDITOR).requestFocus()
        tagged(EditorAreaTestTags.EDITOR).performTextInput("// typed\n")
        settle()
        test.onNodeWithTag(TabStripTestTags.dirty(tabId), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(workbench.document(machine).session.text.snapshot.value.text.startsWith("// typed"))

        workbench.state.saveActive()
        settle()
        test.onNodeWithTag(TabStripTestTags.dirty(tabId), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(Files.readString(machine).startsWith("// typed"))
    }

    @Test
    fun `closing a dirty tab asks and Cancel keeps it, Don't Save closes it`() = workbenchUiTest(dir) {
        openExamples()
        openFromTree(machine)
        val tabId = machine.toString()
        tagged(EditorAreaTestTags.EDITOR).requestFocus()
        tagged(EditorAreaTestTags.EDITOR).performTextInput("x")
        settle()

        tagged(TabStripTestTags.close(tabId)).performClick()
        settle()
        tagged(WorkbenchDialogTestTags.DIALOG).assertIsDisplayed()
        test.onNodeWithText("Save the changes to 'cleaning_robot.kstates'?").assertIsDisplayed()
        tagged(WorkbenchDialogTestTags.button("Cancel")).performClick()
        settle()
        test.onNodeWithTag(WorkbenchDialogTestTags.DIALOG).assertDoesNotExist()
        tagged(TabStripTestTags.tab(tabId)).assertIsDisplayed()

        tagged(TabStripTestTags.close(tabId)).performClick()
        settle()
        tagged(WorkbenchDialogTestTags.button("Don't Save")).performClick()
        settle()
        test.onNodeWithTag(TabStripTestTags.tab(tabId)).assertDoesNotExist()
        assertTrue(!Files.readString(machine).startsWith("x"))
    }

    @Test
    fun `double-clicking a problem navigates to it, a descriptor problem opens externally`() = workbenchUiTest(dir) {
        TestWorkbench.write(root.resolve("broken/broken.kmeta"), "type \"A\" \"a\" {\n    oops\n}\n")
        DescriptorExamples.copyExplicitContext(root.resolve("ctx"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        workbench.open(root)
        settle()
        val broken = root.resolve("broken/broken.kmeta")
        test.onNodeWithText("broken/broken.kmeta (3)").assertIsDisplayed()

        test.onNodeWithTag(TreeViewTestTags.row(ProblemKey.Item(broken, 0))).performMouseInput { doubleClick() }
        settle()
        assertEquals(broken, workbench.state.tabs.active.value)
        val editor = assertNotNull(workbench.state.tabs.editorFor(broken))
        assertEquals(Files.readString(broken).indexOf("oops"), editor.caret.primary.position)

        val descriptor = root.resolve("ctx").resolve(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        test.onNodeWithTag(TreeViewTestTags.row(ProblemKey.Item(descriptor, 0))).performMouseInput { doubleClick() }
        settle()
        assertEquals(listOf(descriptor), workbench.fileOperations.openedExternally)
    }

    @Test
    fun `the problems filter hides warnings`() = workbenchUiTest(dir) {
        DescriptorExamples.copyExplicitContext(root.resolve("ctx"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        workbench.open(root)
        settle()
        val descriptor = root.resolve("ctx").resolve(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        test.onNodeWithTag(TreeViewTestTags.row(ProblemKey.Item(descriptor, 1))).assertExists()

        tagged(ProblemsViewTestTags.filter("warnings")).performClick()
        settle()
        assertEquals(false, workbench.state.problems.state.value.filter.showWarnings)
        test.onNodeWithTag(TreeViewTestTags.row(ProblemKey.Item(descriptor, 1))).assertDoesNotExist()
    }

    @Test
    fun `clicking an outline node moves the caret to it`() = workbenchUiTest(dir) {
        openExamples()
        openFromTree(machine)
        val outline = workbench.state.outline.state.value
        val observe = outline.nodes.single().children.first { it.name == "observe" }
        assertTrue(outline.nodes.isNotEmpty())

        test.onNodeWithText("observe").performClick()
        settle()
        val editor = assertNotNull(workbench.state.tabs.editorFor(machine))
        assertEquals(observe.selectionRange.start, editor.caret.primary.position)
        assertEquals("observe", workbench.state.outline.state.value.highlighted.last().name)
    }

    @Test
    fun `clicking the outline node at the caret moves the caret back to it, and arrow keys keep the focus in the outline`() =
        workbenchUiTest(dir) {
            openExamples()
            openFromTree(machine)
            val observe = workbench.state.outline.state.value.nodes.single().children.first { it.name == "observe" }
            val editor = assertNotNull(workbench.state.tabs.editorFor(machine))
            editor.caret.moveTo(observe.selectionRange.start + "observe".length)
            settle()
            assertEquals("observe", workbench.state.outline.state.value.highlighted.last().name)

            test.onNodeWithText("observe").performClick()
            settle()
            assertEquals(observe.selectionRange.start, editor.caret.primary.position, "the highlighted node navigates too")

            val outline = test.onNode(
                hasTestTag(TreeViewTestTags.TREE) and hasAnyAncestor(hasTestTag(WorkbenchTestTags.toolWindow(ToolWindowId.OUTLINE))),
            )
            outline.requestFocus()
            settle()
            outline.performKeyInput { pressKey(Key.DirectionDown) }
            settle()
            assertEquals(observe.selectionRange.start, editor.caret.primary.position, "browsing does not navigate")
            outline.assertIsFocused()
        }

    @Test
    fun `the status bar shows the caret position, the file's language and the counts`() = workbenchUiTest(dir) {
        openExamples()
        openFromTree(machine)
        test.onNodeWithTag(StatusBarTestTags.item("position")).assertIsDisplayed()
        test.onNodeWithText("Ln 1, Col 1").assertIsDisplayed()
        test.onNodeWithText("KStates").assertIsDisplayed()
        test.onNodeWithText("UTF-8").assertIsDisplayed()
        tagged(StatusBarTestTags.item("errors")).assertIsDisplayed()

        workbench.state.tabs.editorFor(machine)!!.caret.moveTo(Files.readString(machine).indexOf("INITIAL"))
        settle()
        test.onNodeWithText("Ln 5, Col 9").assertIsDisplayed()
    }

    @Test
    fun `the conflict banner offers Reload and Keep mine, and Close for a deleted file`() = workbenchUiTest(dir) {
        openExamples()
        openFromTree(machine)
        tagged(EditorAreaTestTags.EDITOR).requestFocus()
        tagged(EditorAreaTestTags.EDITOR).performTextInput("x")
        Files.writeString(machine, "changed on disk\n")
        workbench.watcher.report(machine, FileChangeKind.MODIFIED)
        settle()
        tagged(EditorAreaTestTags.CONFLICT_BANNER).assertIsDisplayed()
        tagged(EditorAreaTestTags.conflictButton("Keep mine")).assertIsDisplayed()

        tagged(EditorAreaTestTags.conflictButton("Reload")).performClick()
        settle()
        test.onNodeWithTag(EditorAreaTestTags.CONFLICT_BANNER).assertDoesNotExist()
        assertEquals("changed on disk\n", workbench.document(machine).session.text.snapshot.value.text)

        tagged(EditorAreaTestTags.EDITOR).performTextInput("y")
        Files.delete(machine)
        workbench.watcher.report(machine, FileChangeKind.DELETED)
        settle()
        tagged(EditorAreaTestTags.conflictButton("Close")).performClick()
        settle()
        test.onNodeWithTag(TabStripTestTags.tab(machine.toString())).assertDoesNotExist()
    }

    @Test
    fun `a new file from the tree's context menu is named in the dialog, created and opened`() = workbenchUiTest(dir) {
        openExamples()
        workbench.state.projectTree.reveal(machine)
        settle()
        treeRow(robot).performMouseInput { rightClick() }
        settle()
        test.onNodeWithText("New KStates File").performClick()
        settle()
        tagged(WorkbenchDialogTestTags.INPUT).performTextReplacement("second")
        settle()
        tagged(WorkbenchDialogTestTags.button("Create")).performClick()
        settle()

        val created = robot.resolve("second.kstates")
        assertTrue(Files.exists(created))
        assertEquals(created, workbench.state.tabs.active.value)
        tagged(TabStripTestTags.tab(created.toString())).assertIsDisplayed()
    }
}
