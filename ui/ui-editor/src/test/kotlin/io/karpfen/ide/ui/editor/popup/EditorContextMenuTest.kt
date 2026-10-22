package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.geometry.Offset
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor context menu through the overlay contract: the entries by action id with their shared titles at the
 * request position, disabled entries greyed out, choosing an entry runs its action by id and returns focus, Escape
 * dismisses.
 */
@OptIn(ExperimentalTestApi::class)
class EditorContextMenuTest {

    private val titles = listOf("Cut", "Copy", "Paste", "Go to Definition", "Toggle Comment")

    private fun ComposeUiTest.openMenu(harness: PopupHarness, x: Float = 120f, y: Float = 64f) {
        runOnIdle { harness.model.overlays.openContextMenu(ContextMenuRequest(0, x, y)) }
        waitForIdle()
    }

    @Test
    fun `nothing is shown while the menu is closed`() = popupTest("state A") {
        onNodeWithTag(EditorPopupTags.CONTEXT_MENU).assertDoesNotExist()
    }

    @Test
    fun `the menu shows the editor entries with their shared titles at the request position`() = popupTest("state A") { harness ->
        openMenu(harness, x = 120f, y = 64f)

        titles.forEach { onNodeWithText(it).assertIsDisplayed() }
        val menu = onNodeWithTag(EditorPopupTags.CONTEXT_MENU).fetchSemanticsNode().boundsInWindow
        val area = onNodeWithTag(TEXT_AREA).fetchSemanticsNode().boundsInWindow
        assertEquals(area.left + 120f, menu.left, 1f)
        assertEquals(area.top + 64f, menu.top, 1f)
    }

    @Test
    fun `disabled entries are greyed out`() = popupTest("state A") { harness ->
        harness.model.enabledActions.value = setOf(EditorActionIds.COPY, EditorActionIds.PASTE, EditorActionIds.TOGGLE_COMMENT)

        openMenu(harness)

        onNodeWithText("Cut").assertIsNotEnabled()
        onNodeWithText("Go to Definition").assertIsNotEnabled()
        onNodeWithText("Copy").assertIsEnabled()
        onNodeWithText("Toggle Comment").assertIsEnabled()
        val disabled = darkestLuminance("Cut")
        val enabled = darkestLuminance("Copy")
        assertTrue(disabled > enabled + 0.2f, "disabled text is greyed out: darkest luminance $disabled vs $enabled")
    }

    /** The luminance of the darkest pixel of the node showing [text] (its glyphs on the menu background). */
    private fun ComposeUiTest.darkestLuminance(text: String): Float {
        val pixels = onNodeWithText(text).captureToImage().toPixelMap()
        var darkest = 1f
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) darkest = minOf(darkest, pixels[x, y].luminance())
        }
        return darkest
    }

    @Test
    fun `enablement follows the editor while the menu is open`() = popupTest("state A") { harness ->
        harness.model.enabledActions.value = setOf(EditorActionIds.COPY)
        openMenu(harness)
        onNodeWithText("Cut").assertIsNotEnabled()

        runOnIdle { harness.model.enabledActions.value = setOf(EditorActionIds.COPY, EditorActionIds.CUT) }

        onNodeWithText("Cut").assertIsEnabled()
    }

    @Test
    fun `choosing an entry runs its action by id, closes the menu and returns focus`() = popupTest("state A") { harness ->
        openMenu(harness)

        onNodeWithText("Toggle Comment").performClick()

        onNodeWithTag(EditorPopupTags.CONTEXT_MENU).assertDoesNotExist()
        runOnIdle {
            assertEquals(listOf(EditorActionIds.TOGGLE_COMMENT), harness.model.executed)
            assertTrue(harness.model.plain.startsWith("//"), "toggled: ${harness.model.plain}")
            assertNull(harness.model.overlays.contextMenu.value)
            assertEquals(1, harness.model.focusRequests)
        }
    }

    @Test
    fun `Escape dismisses the menu and returns focus`() = popupTest("state A") { harness ->
        openMenu(harness)
        onNodeWithText("Copy").requestFocus()

        onNodeWithText("Copy").performKeyInput { pressKey(Key.Escape) }

        onNodeWithTag(EditorPopupTags.CONTEXT_MENU).assertDoesNotExist()
        runOnIdle {
            assertNull(harness.model.overlays.contextMenu.value)
            assertTrue(harness.model.executed.isEmpty())
            assertEquals(1, harness.model.focusRequests)
        }
    }

    @Test
    fun `a click outside dismisses the menu and returns focus`() = popupTest("state A") { harness ->
        openMenu(harness, x = 120f, y = 64f)

        onNodeWithTag(TEXT_AREA).performMouseInput { click(Offset(400f, 400f)) }

        onNodeWithTag(EditorPopupTags.CONTEXT_MENU).assertDoesNotExist()
        runOnIdle {
            assertNull(harness.model.overlays.contextMenu.value)
            assertTrue(harness.model.executed.isEmpty())
            assertEquals(1, harness.model.focusRequests)
        }
    }

    @Test
    fun `custom entries use their own titles`() = popupTest(
        "state A",
        popups = EditorPopups(listOf(EditorContextMenuItem(EditorActionIds.UNDO), EditorContextMenuItem(EditorActionIds.SELECT_ALL, "Everything"))),
    ) { harness ->
        openMenu(harness)

        onNodeWithText("Undo").assertIsDisplayed()
        onNodeWithText("Everything").assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(listOf(EditorActionIds.SELECT_ALL), harness.model.executed)
            assertEquals(7, harness.model.caret.primary.selection.length)
        }
    }
}
