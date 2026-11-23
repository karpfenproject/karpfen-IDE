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
package io.karpfen.ide.ui.workbench

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.width
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.ui.diagram.DiagramViewTestTags
import io.karpfen.ide.ui.foundation.layout.ShareSplitPaneTestTags
import io.karpfen.ide.ui.foundation.widgets.TabStripTestTags
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.PaneNode
import io.karpfen.ide.ui.workbench.tabs.SplitOrientation
import io.karpfen.ide.ui.workbench.tabs.SplitSide
import io.karpfen.ide.ui.workbench.tabs.view.EditorAreaTestTags
import io.karpfen.ide.ui.workbench.testing.switchOnLanguageFeatures
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The split editor in the composed workbench. A split shows the file in a second pane with an editor of its own on the
 * same document. The four buttons of a pane split that pane. The pane that was pressed or focused last is the active
 * one, and Close Tab and opening a file act on it. A drag of the border between two panes sizes them. A pane whose
 * last tab closes disappears, and only the last view of a changed file asks about its changes. Each pane shows its
 * file in its own view mode.
 */
@OptIn(ExperimentalTestApi::class)
class SplitPanesUiTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve(DescriptorExamples.CONTEXT_DIRECTORY)
    private val machine: Path get() = robot.resolve("cleaning_robot.kstates")
    private val metamodel: Path get() = robot.resolve("cleaning_robot.kmeta")

    /**
     * Opens the examples with every language feature switched on, hides the Outline, so two panes side by side have
     * room for their tabs, and opens [machine] from the project tree.
     */
    private suspend fun WorkbenchUi.openMachine() {
        Examples.copyTo(root)
        switchOnLanguageFeatures(root)
        workbench.open(root)
        settle()
        workbench.state.layout.toggleToolWindow(ToolWindowId.OUTLINE)
        openFromTree(machine)
    }

    /** Opens [path] from the project tree by double-click. */
    private fun WorkbenchUi.openFromTree(path: Path) {
        workbench.state.projectTree.reveal(path)
        settle()
        treeRow(path).performMouseInput { doubleClick() }
        settle()
    }

    /** The ids of the panes in focus order. */
    private fun WorkbenchUi.panes(): List<PaneId> = workbench.state.tabs.layout.value.panes.map { it.id }

    /** The node tagged [tag] inside the pane [pane]. */
    private fun WorkbenchUi.inPane(pane: PaneId, tag: String, unmerged: Boolean = false): SemanticsNodeInteraction =
        test.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(EditorAreaTestTags.pane(pane.value))), useUnmergedTree = unmerged)

    /** Splits the only pane to the right with its button and returns the left and the right pane. */
    private fun WorkbenchUi.splitRight(): Pair<PaneId, PaneId> {
        val only = panes().single()
        inPane(only, EditorAreaTestTags.splitButton(SplitSide.RIGHT.name)).performClick()
        settle()
        val (left, right) = panes()
        assertEquals(only, left)
        return left to right
    }

    @Test
    fun `a split shows the file in two panes, and an edit in one shows in the other`() = workbenchUiTest(dir) {
        openMachine()
        val (left, right) = splitRight()
        val tab = machine.toString()

        inPane(left, EditorAreaTestTags.EDITOR).assertIsDisplayed()
        inPane(right, EditorAreaTestTags.EDITOR).assertIsDisplayed()
        inPane(left, TabStripTestTags.tab(tab)).assertIsDisplayed()
        inPane(right, TabStripTestTags.tab(tab)).assertIsDisplayed()
        assertEquals(right, workbench.state.tabs.activePane.value, "the new pane is the active one")

        inPane(right, EditorAreaTestTags.EDITOR).requestFocus()
        inPane(right, EditorAreaTestTags.EDITOR).performTextInput("// right\n")
        settle()

        val leftEditor = assertNotNull(workbench.state.tabs.editorFor(left, machine))
        val rightEditor = assertNotNull(workbench.state.tabs.editorFor(right, machine))
        assertNotSame(leftEditor, rightEditor, "each pane has an editor of its own")
        assertTrue(leftEditor.session.text.snapshot.value.text.startsWith("// right"), "both edit the one document")
        inPane(left, TabStripTestTags.dirty(tab), unmerged = true).assertIsDisplayed()
        inPane(right, TabStripTestTags.dirty(tab), unmerged = true).assertIsDisplayed()
        assertEquals("// right\n".length, rightEditor.caret.primary.position)
    }

    @Test
    fun `the buttons of a pane split that pane, and without a file there is nothing to split`() = workbenchUiTest(dir) {
        Examples.copyTo(root)
        workbench.open(root)
        settle()
        test.onAllNodesWithTag(EditorAreaTestTags.splitButton(SplitSide.RIGHT.name)).assertCountEquals(0)

        openFromTree(machine)
        val (left, right) = splitRight()
        inPane(left, EditorAreaTestTags.splitButton(SplitSide.DOWN.name)).performClick()
        settle()

        val layout = workbench.state.tabs.layout.value
        val top = assertIs<PaneNode.Split>(layout.root)
        assertEquals(SplitOrientation.ROW, top.orientation)
        val column = assertIs<PaneNode.Split>(top.first)
        assertEquals(SplitOrientation.COLUMN, column.orientation)
        assertEquals(left, assertIs<PaneNode.Pane>(column.first).id, "the pane that was split stays on top")
        val below = assertIs<PaneNode.Pane>(column.second)
        assertEquals(listOf(machine), below.tabs)
        assertEquals(below.id, layout.activePane, "the pane below is the active one")
        assertEquals(right, assertIs<PaneNode.Pane>(top.second).id)
        test.onAllNodesWithTag(EditorAreaTestTags.EDITOR).assertCountEquals(3)
    }

    @Test
    fun `the active pane follows a press and the focus, and opening and Close Tab act on it`() = workbenchUiTest(dir) {
        openMachine()
        val (left, right) = splitRight()

        inPane(left, TabStripTestTags.tab(machine.toString())).performClick()
        settle()
        assertEquals(left, workbench.state.tabs.activePane.value, "a press on the tab strip of the left pane")

        openFromTree(metamodel)
        assertEquals(listOf(machine, metamodel), workbench.state.tabs.panes.value.getValue(left).tabs.map { it.path })
        assertEquals(listOf(machine), workbench.state.tabs.panes.value.getValue(right).tabs.map { it.path })

        inPane(right, EditorAreaTestTags.EDITOR).requestFocus()
        settle()
        assertEquals(right, workbench.state.tabs.activePane.value, "the focus in the editor of the right pane")
        assertEquals(machine, workbench.state.tabs.active.value)

        inPane(left, EditorAreaTestTags.EDITOR).performMouseInput { click(center) }
        settle()
        assertEquals(left, workbench.state.tabs.activePane.value, "a click into the editor of the left pane")
        workbench.state.closeActiveTab()
        settle()

        assertEquals(listOf(machine), workbench.state.tabs.panes.value.getValue(left).tabs.map { it.path }, "Close Tab closed the metamodel")
        assertEquals(listOf(machine), workbench.state.tabs.panes.value.getValue(right).tabs.map { it.path }, "only in the active pane")
    }

    @Test
    fun `a pane that is split keeps the zoom of its diagram, and the new pane has its own`() = workbenchUiTest(dir) {
        openMachine()
        val only = panes().single()
        inPane(only, EditorAreaTestTags.viewMode("Diagram")).performClick()
        settle()
        test.waitUntil(timeoutMillis = 10_000) { test.onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().isNotEmpty() }
        val fitted = zoomOf(only)
        inPane(only, DiagramViewTestTags.ZOOM_IN).performClick()
        settle()
        val zoomed = zoomOf(only)
        assertTrue(zoomed != fitted, "the zoom changed: $fitted -> $zoomed")

        inPane(only, EditorAreaTestTags.splitButton(SplitSide.DOWN.name)).performClick()
        settle()
        val below = panes().last()
        test.waitUntil(timeoutMillis = 10_000) { test.onAllNodesWithTag(DiagramViewTestTags.ZOOM).fetchSemanticsNodes().size == 2 }

        assertEquals(zoomed, zoomOf(only), "the pane moved into the new split with its diagram as it was")
        assertEquals(EditorViewMode.DIAGRAM, workbench.state.tabs.panes.value.getValue(below).viewMode, "the new pane took the view mode")
        assertTrue(zoomOf(below) != zoomed, "the new pane fitted its diagram with a zoom of its own: ${zoomOf(below)}")
    }

    /** The zoom of the diagram of the pane [pane] as its zoom button shows it. */
    private fun WorkbenchUi.zoomOf(pane: PaneId): String =
        inPane(pane, DiagramViewTestTags.ZOOM, unmerged = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()

    @Test
    fun `a drag of the border between two panes sizes them`() = workbenchUiTest(dir) {
        openMachine()
        val (left, right) = splitRight()
        val before = inPane(left, EditorAreaTestTags.EDITOR).getUnclippedBoundsInRoot()

        tagged(ShareSplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(10f, 0f)) }
            release()
        }
        settle()

        val split = assertIs<PaneNode.Split>(workbench.state.tabs.layout.value.root)
        assertTrue(split.share > 0.5f, "the left pane takes more than half: ${split.share}")
        val after = inPane(left, EditorAreaTestTags.EDITOR).getUnclippedBoundsInRoot()
        assertTrue(after.width > before.width, "the left editor grew: ${before.width} -> ${after.width}")
        inPane(right, EditorAreaTestTags.EDITOR).assertIsDisplayed()
    }

    @Test
    fun `a pane whose last tab closes disappears, and only the last view of a changed file asks`() = workbenchUiTest(dir) {
        openMachine()
        val (left, right) = splitRight()
        inPane(right, EditorAreaTestTags.EDITOR).requestFocus()
        inPane(right, EditorAreaTestTags.EDITOR).performTextInput("x")
        settle()

        inPane(right, TabStripTestTags.close(machine.toString())).performClick()
        settle()

        assertNull(workbench.state.dialogs.current.value, "the left pane still shows the changed file")
        assertEquals(listOf(left), panes(), "the right pane disappeared")
        assertEquals(left, workbench.state.tabs.activePane.value, "its neighbour took its place and is active")
        test.onAllNodesWithTag(ShareSplitPaneTestTags.DIVIDER).assertCountEquals(0)
        assertTrue(workbench.document(machine).isDirty.value, "the change is kept")

        inPane(left, TabStripTestTags.close(machine.toString())).performClick()
        settle()
        assertIs<DialogRequest.UnsavedChanges>(workbench.state.dialogs.current.value, "the last view asks")
    }

    @Test
    fun `each pane shows its file in its own view mode, and a press on a diagram makes its pane active`() = workbenchUiTest(dir) {
        openMachine()
        val (left, right) = splitRight()

        inPane(right, EditorAreaTestTags.viewMode("Diagram")).performClick()
        settle()
        inPane(right, EditorAreaTestTags.DIAGRAM).assertIsDisplayed()
        inPane(right, EditorAreaTestTags.EDITOR).assertDoesNotExist()
        inPane(left, EditorAreaTestTags.EDITOR).assertIsDisplayed()
        inPane(left, EditorAreaTestTags.DIAGRAM).assertDoesNotExist()

        inPane(left, EditorAreaTestTags.viewMode("Split")).performClick()
        settle()
        inPane(left, EditorAreaTestTags.DIAGRAM).assertIsDisplayed()
        assertEquals(EditorViewMode.SPLIT, workbench.state.tabs.panes.value.getValue(left).viewMode)
        assertEquals(EditorViewMode.DIAGRAM, workbench.state.tabs.panes.value.getValue(right).viewMode)
        assertEquals(left, workbench.state.tabs.activePane.value)

        inPane(right, EditorAreaTestTags.DIAGRAM).performMouseInput { click(topLeft + Offset(4f, 40f)) }
        settle()
        assertEquals(right, workbench.state.tabs.activePane.value, "a diagram takes no focus, the press makes the pane active")
    }

    @Test
    fun `text and diagram of a pane keep their shares of the width when the pane is split side by side`() = workbenchUiTest(dir) {
        openMachine()
        val only = panes().single()
        inPane(only, EditorAreaTestTags.viewMode("Split")).performClick()
        settle()
        inPane(only, EditorAreaTestTags.splitButton(SplitSide.RIGHT.name)).performClick()
        settle()

        val text = inPane(only, EditorAreaTestTags.EDITOR).getUnclippedBoundsInRoot().width
        val diagram = inPane(only, EditorAreaTestTags.DIAGRAM).getUnclippedBoundsInRoot().width
        assertTrue(text > (text + diagram) * 0.4f && diagram > (text + diagram) * 0.4f, "text $text and diagram $diagram")
    }

    @Test
    fun `the keyboard focus follows the active pane, also into a pane that shows a diagram alone`() = workbenchUiTest(dir) {
        openMachine()
        val (left, right) = splitRight()
        inPane(right, EditorAreaTestTags.viewMode("Diagram")).performClick()
        settle()
        val leftEditor = assertNotNull(workbench.state.tabs.editorFor(left, machine))
        val rightEditor = assertNotNull(workbench.state.tabs.editorFor(right, machine))

        inPane(left, EditorAreaTestTags.EDITOR).performMouseInput { click(center) }
        settle()
        assertTrue(leftEditor.isFocused.value)
        inPane(right, EditorAreaTestTags.DIAGRAM).performMouseInput { click(topLeft + Offset(4f, 40f)) }
        settle()
        assertEquals(right, workbench.state.tabs.activePane.value)
        assertFalse(leftEditor.isFocused.value, "a press on the diagram takes the focus from the other pane")

        workbench.state.tabs.focusNextPane()
        settle()
        assertEquals(left, workbench.state.tabs.activePane.value)
        assertTrue(leftEditor.isFocused.value, "F6 into a pane that shows its text focuses its editor")
        workbench.state.tabs.focusNextPane()
        settle()
        assertEquals(right, workbench.state.tabs.activePane.value)
        assertFalse(leftEditor.isFocused.value, "F6 into a pane that shows a diagram alone takes the focus away")

        inPane(right, EditorAreaTestTags.viewMode("Text")).performClick()
        settle()
        assertTrue(rightEditor.isFocused.value)
        inPane(left, TabStripTestTags.close(machine.toString())).performMouseInput { click(center) }
        settle()
        assertEquals(listOf(right), panes())
        assertTrue(rightEditor.isFocused.value, "the pane that takes the place of the closed one keeps the focus")

        workbench.state.splitActivePane(SplitSide.DOWN)
        settle()
        val lower = panes().last()
        val lowerEditor = assertNotNull(workbench.state.tabs.editorFor(lower, machine))
        assertTrue(lowerEditor.isFocused.value)
        workbench.state.closeActiveTab()
        settle()
        assertEquals(listOf(right), panes())
        assertTrue(rightEditor.isFocused.value, "the neighbour of a closed active pane takes the focus")
    }

    @Test
    fun `the last pane stays, empty and without split buttons, when its last tab closes`() = workbenchUiTest(dir) {
        openMachine()
        val only = panes().single()
        inPane(only, EditorAreaTestTags.splitButton(SplitSide.UP.name)).assertIsDisplayed()
        workbench.state.closeTab(only, machine)
        settle()

        test.onAllNodesWithTag(EditorAreaTestTags.splitButton(SplitSide.UP.name)).assertCountEquals(0)
        assertEquals(listOf(only), panes(), "the last pane stays, empty")
        test.onAllNodesWithTag(EditorAreaTestTags.AREA).assertCountEquals(1)
    }
}
