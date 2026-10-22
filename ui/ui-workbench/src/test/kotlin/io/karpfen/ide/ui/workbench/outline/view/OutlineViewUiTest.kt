package io.karpfen.ide.ui.workbench.outline.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.foundation.widgets.TreeViewTestTags
import io.karpfen.ide.ui.workbench.outline.OutlineUiState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [OutlineView] headless: every click navigates, also on the highlighted node (the one containing the caret); the arrow
 * keys browse the outline without navigating, Enter navigates to the browsed node.
 */
@OptIn(ExperimentalTestApi::class)
class OutlineViewUiTest {

    private val path: Path = Path.of("/ws/robot.kstates").toAbsolutePath()
    private val idle = node("idle", TextRange(0, 100), TextRange(7, 11))
    private val drive = node("drive", TextRange(101, 200), TextRange(108, 113))
    private val nodes = listOf(idle, drive)

    private fun node(name: String, range: TextRange, selection: TextRange) =
        OutlineNode(SymbolKind.STATE, name, null, range, selection, emptyList())

    /**
     * Shows the outline of [nodes] with the caret in [highlighted] and returns the ranges [OutlineView] navigates to.
     * Like the workbench, a navigation moves the caret to the node, which then contains it and becomes the highlighted
     * one.
     */
    private fun ComposeUiTest.outline(highlighted: OutlineNode): MutableList<TextRange> {
        val navigations = mutableListOf<TextRange>()
        var state by mutableStateOf(OutlineUiState(path, version = 0, nodes = nodes, highlighted = listOf(highlighted)))
        setContent {
            KarpfenTheme(darkTheme = false) {
                Box(Modifier.size(300.dp, 400.dp)) {
                    OutlineView(state, onNavigate = { location: Location ->
                        navigations += location.range
                        state = state.copy(highlighted = nodes.filter { location.range in it.range })
                    })
                }
            }
        }
        return navigations
    }

    @Test
    fun `clicking the node that contains the caret navigates to it`() = runComposeUiTest {
        val navigations = outline(highlighted = idle)

        onNodeWithText("idle").performClick()
        waitForIdle()

        assertEquals(listOf(idle.selectionRange), navigations, "back to the node after scrolling the editor away")
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("drive").performClick()
        waitForIdle()
        assertEquals(listOf(idle.selectionRange, drive.selectionRange), navigations)
    }

    @Test
    fun `arrow keys browse the outline without navigating, Enter navigates`() = runComposeUiTest {
        val navigations = outline(highlighted = idle)
        val tree = onNodeWithTag(TreeViewTestTags.TREE)
        tree.requestFocus()

        tree.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()

        assertEquals(emptyList(), navigations, "Down moves the selection only")
        onNodeWithTag(TreeViewTestTags.row(OutlineKey(listOf(1)))).assertIsSelected()
        tree.assertIsFocused()

        tree.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(drive.selectionRange), navigations)
    }
}
