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
package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless UI tests of [TreeView]: expanding and collapsing, selection, keyboard and mouse navigation, the context menu
 * and the icon slot, and the rule that every change of `selected` scrolls the selected row into view.
 */
@OptIn(ExperimentalTestApi::class)
class TreeViewTest {

    /** The hoisted state of a tree under test. The callbacks record what the tree asked for and apply it. */
    private class Harness(roots: List<TreeNode<String>>, expanded: Set<String> = emptySet(), selected: String? = null) {
        var roots by mutableStateOf(roots)
        var expanded by mutableStateOf(expanded)
        var selected by mutableStateOf(selected)
        val toggled = mutableListOf<String>()
        val selections = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val clicks = mutableListOf<String>()
    }

    /**
     * A small project tree:
     * ```
     * src (expandable) ─ a (expandable) ─ x.kmeta
     *                  ├ b.kstates
     *                  └ empty (expandable, no children)
     * README.md
     * ```
     */
    private val project = listOf(
        TreeNode(
            "src",
            "src",
            children = listOf(
                TreeNode("src/a", "a", children = listOf(TreeNode("src/a/x.kmeta", "x.kmeta"))),
                TreeNode("src/b.kstates", "b.kstates"),
                TreeNode("src/empty", "empty", children = emptyList()),
            ),
        ),
        TreeNode("README.md", "README.md"),
    )

    private fun flat(count: Int): List<TreeNode<String>> = List(count) { TreeNode("n$it", "node $it") }

    private fun ComposeUiTest.tree(
        harness: Harness,
        height: Dp = 300.dp,
        icon: (@Composable (TreeNode<String>) -> Unit)? = null,
        contextMenu: (TreeNode<String>) -> List<ContextMenuAction> = { emptyList() },
    ) {
        setThemedContent {
            Box(Modifier.size(240.dp, height)) {
                TreeView(
                    roots = harness.roots,
                    expanded = harness.expanded,
                    selected = harness.selected,
                    onToggleExpanded = { key ->
                        harness.toggled += key
                        harness.expanded = if (key in harness.expanded) harness.expanded - key else harness.expanded + key
                    },
                    onSelect = { key ->
                        harness.selections += key
                        harness.selected = key
                    },
                    onOpen = { key -> harness.opened += key },
                    modifier = Modifier.fillMaxSize(),
                    icon = icon,
                    contextMenu = contextMenu,
                    onClick = { key -> harness.clicks += key },
                )
            }
        }
    }

    private fun ComposeUiTest.row(key: String) = onNodeWithTag(TreeViewTestTags.row(key))

    private fun ComposeUiTest.press(key: Key, times: Int = 1) {
        onNodeWithTag(TreeViewTestTags.TREE).performKeyInput { repeat(times) { pressKey(key) } }
    }

    /** Clicks the chevron of the row [key] at nesting [depth]. */
    private fun ComposeUiTest.clickChevron(key: String, depth: Int) {
        val x = with(density) { (TreeViewDefaults.chevronStart(depth) + TreeViewDefaults.ChevronWidth / 2).toPx() }
        row(key).performMouseInput { click(Offset(x, centerY)) }
    }

    /** Asserts that the row [key] lies completely inside the tree's viewport. */
    private fun ComposeUiTest.assertRowInViewport(key: String) {
        val tree = onNodeWithTag(TreeViewTestTags.TREE).getUnclippedBoundsInRoot()
        val row = row(key).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(row.top.value >= tree.top.value - 0.5f && row.bottom.value <= tree.bottom.value + 0.5f, "$key: $row in $tree")
    }

    @Test
    fun `collapsed roots show only the roots and clicking a chevron expands and collapses`() = runComposeUiTest {
        val harness = Harness(project)
        tree(harness)

        row("src").assertIsDisplayed()
        row("README.md").assertIsDisplayed()
        row("src/a").assertDoesNotExist()

        clickChevron("src", depth = 0)
        waitForIdle()
        assertEquals(listOf("src"), harness.toggled)
        assertEquals(emptyList(), harness.selections, "a chevron click does not select")
        row("src/a").assertIsDisplayed()
        row("src/b.kstates").assertIsDisplayed()
        row("src/a/x.kmeta").assertDoesNotExist()

        clickChevron("src/a", depth = 1)
        waitForIdle()
        row("src/a/x.kmeta").assertIsDisplayed()

        clickChevron("src", depth = 0)
        waitForIdle()
        assertEquals(listOf("src", "src/a", "src"), harness.toggled)
        row("src/a").assertDoesNotExist()
        row("src/a/x.kmeta").assertDoesNotExist()
    }

    @Test
    fun `children are indented by their depth`() = runComposeUiTest {
        tree(Harness(project, expanded = setOf("src", "src/a")))

        val labelLeft = { text: String -> onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().left }
        assertEquals(TreeViewDefaults.Indent.value, (labelLeft("a") - labelLeft("src")).value, 0.5f)
        assertEquals(TreeViewDefaults.Indent.value, (labelLeft("x.kmeta") - labelLeft("a")).value, 0.5f)
    }

    @Test
    fun `clicking a row selects it and only it`() = runComposeUiTest {
        val harness = Harness(project, expanded = setOf("src"))
        tree(harness)

        row("src/b.kstates").performClick()
        waitForIdle()

        assertEquals(listOf("src/b.kstates"), harness.selections)
        row("src/b.kstates").assertIsSelected()
        row("src").assertIsNotSelected()
        row("README.md").assertIsNotSelected()

        mainClock.advanceTimeBy(1_000)
        row("src/b.kstates").performClick()
        waitForIdle()
        assertEquals(listOf("src/b.kstates"), harness.selections, "selecting the selected row again reports nothing")
        assertEquals(emptyList(), harness.opened)
    }

    @Test
    fun `every click on a row is reported, also on the selected row, but not keys or chevron clicks`() = runComposeUiTest {
        val harness = Harness(project, expanded = setOf("src"), selected = "src/b.kstates")
        tree(harness)

        row("src/b.kstates").performClick()
        waitForIdle()
        assertEquals(listOf("src/b.kstates"), harness.clicks, "a click on the selected row")
        assertEquals(emptyList(), harness.selections, "which changes no selection")

        mainClock.advanceTimeBy(1_000)
        row("README.md").performClick()
        press(Key.DirectionUp)
        clickChevron("src", depth = 0)
        waitForIdle()
        assertEquals(listOf("src/b.kstates", "README.md"), harness.clicks)
        assertEquals(listOf("README.md", "src/empty"), harness.selections)
    }

    @Test
    fun `double-clicking a row selects and opens it`() = runComposeUiTest {
        val harness = Harness(project, expanded = setOf("src"))
        tree(harness)

        row("src/b.kstates").performMouseInput { doubleClick() }
        waitForIdle()

        assertEquals(listOf("src/b.kstates"), harness.selections)
        assertEquals(listOf("src/b.kstates"), harness.opened)
    }

    @Test
    fun `Up and Down move the selection`() = runComposeUiTest {
        val harness = Harness(project, expanded = setOf("src"))
        tree(harness)
        row("src").performClick()

        press(Key.DirectionDown, times = 2)
        waitForIdle()
        assertEquals("src/b.kstates", harness.selected)
        row("src/b.kstates").assertIsSelected()

        press(Key.DirectionUp)
        waitForIdle()
        assertEquals(listOf("src", "src/a", "src/b.kstates", "src/a"), harness.selections)
    }

    @Test
    fun `Up at the first row and Down at the last row change nothing`() = runComposeUiTest {
        val harness = Harness(project, selected = "README.md")
        tree(harness)
        onNodeWithTag(TreeViewTestTags.TREE).requestFocus()

        press(Key.DirectionDown)
        waitForIdle()
        assertEquals("README.md", harness.selected)

        press(Key.DirectionUp, times = 3)
        waitForIdle()
        assertEquals(listOf("src"), harness.selections)
    }

    @Test
    fun `Right expands, then moves to the first child, and Left collapses, then moves to the parent`() = runComposeUiTest {
        val harness = Harness(project)
        tree(harness)
        row("src").performClick()

        press(Key.DirectionRight)
        waitForIdle()
        assertEquals(setOf("src"), harness.expanded)
        assertEquals("src", harness.selected)
        row("src/a").assertIsDisplayed()

        press(Key.DirectionRight)
        waitForIdle()
        assertEquals("src/a", harness.selected)

        press(Key.DirectionRight, times = 2)
        waitForIdle()
        assertEquals("src/a/x.kmeta", harness.selected)

        press(Key.DirectionLeft)
        waitForIdle()
        assertEquals("src/a", harness.selected, "a leaf moves to its parent")

        press(Key.DirectionLeft)
        waitForIdle()
        assertEquals(setOf("src"), harness.expanded, "an expanded node collapses")
        row("src/a/x.kmeta").assertDoesNotExist()

        press(Key.DirectionLeft, times = 2)
        waitForIdle()
        assertEquals("src", harness.selected)
        assertEquals(emptySet(), harness.expanded)
        assertEquals(listOf("src", "src/a", "src/a", "src"), harness.toggled)
    }

    @Test
    fun `Enter opens the selected node`() = runComposeUiTest {
        val harness = Harness(project, expanded = setOf("src"))
        tree(harness)
        row("src/b.kstates").performClick()

        press(Key.Enter)
        waitForIdle()

        assertEquals(listOf("src/b.kstates"), harness.opened)
    }

    @Test
    fun `Home and End select the first and the last row`() = runComposeUiTest {
        val harness = Harness(flat(100), selected = "n50")
        tree(harness)
        onNodeWithTag(TreeViewTestTags.TREE).requestFocus()

        press(Key.MoveEnd)
        waitForIdle()
        assertEquals("n99", harness.selected)
        assertRowInViewport("n99")

        press(Key.MoveHome)
        waitForIdle()
        assertEquals("n0", harness.selected)
        assertRowInViewport("n0")
    }

    @Test
    fun `modified navigation keys are left to other handlers`() = runComposeUiTest {
        val harness = Harness(project, selected = "src")
        tree(harness)
        onNodeWithTag(TreeViewTestTags.TREE).requestFocus()

        onNodeWithTag(TreeViewTestTags.TREE).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        onNodeWithTag(TreeViewTestTags.TREE).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
        waitForIdle()

        assertEquals(emptyList(), harness.selections)
        assertEquals(emptyList(), harness.opened)
    }

    @Test
    fun `moving the selection with the keyboard scrolls it into view`() = runComposeUiTest {
        val harness = Harness(flat(200), selected = "n0")
        tree(harness, height = 220.dp)
        onNodeWithTag(TreeViewTestTags.TREE).requestFocus()

        repeat(30) { press(Key.DirectionDown) }
        waitForIdle()

        assertEquals("n30", harness.selected)
        assertRowInViewport("n30")
    }

    @Test
    fun `a programmatic selection change scrolls an off-screen row into view`() = runComposeUiTest {
        val harness = Harness(flat(200))
        tree(harness, height = 220.dp)
        row("n150").assertDoesNotExist()

        harness.selected = "n150"
        waitForIdle()
        assertRowInViewport("n150")
        row("n150").assertIsSelected()

        harness.selected = "n3"
        waitForIdle()
        assertRowInViewport("n3")
        assertEquals(emptyList(), harness.selections, "a programmatic change is not reported back")
    }

    @Test
    fun `revealing scrolls minimally, aligning the row with the nearer edge`() = runComposeUiTest {
        val harness = Harness(flat(200))
        tree(harness, height = 220.dp)
        val treeBounds = onNodeWithTag(TreeViewTestTags.TREE).getUnclippedBoundsInRoot()

        harness.selected = "n40"
        waitForIdle()
        assertEquals(treeBounds.bottom.value, row("n40").getUnclippedBoundsInRoot().bottom.value, 0.5f, "below: bottom edge")

        harness.selected = "n35"
        waitForIdle()
        assertEquals(treeBounds.bottom.value, row("n40").getUnclippedBoundsInRoot().bottom.value, 0.5f, "visible: no scroll")

        harness.selected = "n10"
        waitForIdle()
        assertEquals(treeBounds.top.value, row("n10").getUnclippedBoundsInRoot().top.value, 0.5f, "above: top edge")
    }

    @Test
    fun `a selection inside a collapsed folder is revealed once the folder is expanded`() = runComposeUiTest {
        val children = List(40) { TreeNode("dir/f$it", "f$it") }
        val harness = Harness(listOf(TreeNode("dir", "dir", children = children)) + flat(5))
        tree(harness, height = 220.dp)

        harness.selected = "dir/f35"
        waitForIdle()
        row("dir/f35").assertDoesNotExist()

        harness.expanded = setOf("dir")
        waitForIdle()
        assertRowInViewport("dir/f35")
    }

    @Test
    fun `right-clicking a row selects it and opens its context menu`() = runComposeUiTest {
        val harness = Harness(project, expanded = setOf("src"))
        val renamed = mutableListOf<String>()
        tree(
            harness,
            contextMenu = { node ->
                listOf(
                    ContextMenuAction("Rename") { renamed += node.key },
                    ContextMenuAction("Delete", isEnabled = false) { error("disabled entries never run") },
                )
            },
        )

        row("src/b.kstates").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals("src/b.kstates", harness.selected)
        onNodeWithText("Rename").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Delete").assertIsDisplayed().assertIsNotEnabled()

        onNodeWithText("Rename").performClick()
        waitForIdle()
        assertEquals(listOf("src/b.kstates"), renamed)
        onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun `a node without context-menu entries opens no menu`() = runComposeUiTest {
        val harness = Harness(project)
        tree(harness, contextMenu = { node -> if (node.key == "src") listOf(ContextMenuAction("New File") {}) else emptyList() })

        row("README.md").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals("README.md", harness.selected)
        onNodeWithText("New File").assertDoesNotExist()
    }

    @Test
    fun `a menu whose row vanishes closes and does not come back with the row`() = runComposeUiTest {
        val harness = Harness(project)
        tree(harness, contextMenu = { listOf(ContextMenuAction("Reveal") {}) })
        row("README.md").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Reveal").assertIsDisplayed()

        harness.roots = project.take(1)
        waitForIdle()
        onNodeWithText("Reveal").assertDoesNotExist()

        harness.roots = project
        waitForIdle()
        row("README.md").assertIsDisplayed()
        onNodeWithText("Reveal").assertDoesNotExist()
    }

    @Test
    fun `the icon slot renders each visible node's icon`() = runComposeUiTest {
        tree(
            Harness(project, expanded = setOf("src")),
            icon = { node -> Box(Modifier.size(16.dp).testTag("icon:${node.key}")) },
        )

        listOf("src", "src/a", "src/b.kstates", "src/empty", "README.md").forEach { key ->
            onNodeWithTag("icon:$key", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `a changed forest replaces the rows`() = runComposeUiTest {
        val harness = Harness(project)
        tree(harness)

        harness.roots = listOf(TreeNode("models", "models", children = emptyList()))
        waitForIdle()

        row("models").assertIsDisplayed()
        row("src").assertDoesNotExist()
    }
}
