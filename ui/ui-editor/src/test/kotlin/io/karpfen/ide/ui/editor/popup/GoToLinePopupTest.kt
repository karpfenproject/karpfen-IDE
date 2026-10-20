package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The go-to-line popup through the overlay contract: `line[:col]` input, the hint, Enter / Go through
 * `EditorPopupModel.goToLine`, invalid input, Escape returning focus and dismissal on focus loss (spec §7.2).
 */
@OptIn(ExperimentalTestApi::class)
class GoToLinePopupTest {

    private val text = "a\nbb\nccc\ndddd\neeeee"

    private fun goToLineTest(block: ComposeUiTest.(PopupHarness) -> Unit) = popupTest(text, caretAt = text.indexOf("ccc") + 1) { harness ->
        runOnIdle { harness.model.overlays.openGoToLine() }
        waitForIdle()
        block(harness)
    }

    private fun ComposeUiTest.field() = onNodeWithTag(EditorPopupTags.GO_TO_LINE_FIELD)

    @Test
    fun `nothing is shown while the popup is closed`() = popupTest(text) {
        onNodeWithTag(EditorPopupTags.GO_TO_LINE).assertDoesNotExist()
    }

    @Test
    fun `the popup takes focus and shows the current line and the line count`() = goToLineTest {
        field().assertIsFocused()
        onNodeWithTag(EditorPopupTags.GO_TO_LINE_HINT).assertTextEquals("Current line 3 of 5")
    }

    @Test
    fun `Enter goes to line and column through the model`() = goToLineTest { harness ->
        field().performTextInput("4:3")
        field().performKeyInput { pressKey(Key.Enter) }

        onNodeWithTag(EditorPopupTags.GO_TO_LINE).assertDoesNotExist()
        runOnIdle {
            assertEquals(listOf(GoToLineQuery(4, 3)), harness.model.goToLineQueries)
            assertEquals(text.indexOf("dddd") + 2, harness.model.caret.primary.position)
            assertEquals(1, harness.model.focusRequests)
        }
    }

    @Test
    fun `the Go button goes to a line without column`() = goToLineTest { harness ->
        field().performTextInput(" 2 ")
        onNodeWithContentDescription("Go to Line").assertIsEnabled().performClick()

        runOnIdle { assertEquals(listOf(GoToLineQuery(2)), harness.model.goToLineQueries) }
    }

    @Test
    fun `invalid input disables Go and Enter does nothing`() = goToLineTest { harness ->
        onNodeWithContentDescription("Go to Line").assertIsNotEnabled()

        field().performTextInput("3:x")
        field().performKeyInput { pressKey(Key.Enter) }

        onNodeWithContentDescription("Go to Line").assertIsNotEnabled()
        onNodeWithTag(EditorPopupTags.GO_TO_LINE).assertExists()
        runOnIdle {
            assertTrue(harness.model.goToLineQueries.isEmpty())
            assertTrue(harness.model.overlays.goToLine.value)
        }
    }

    @Test
    fun `Escape cancels and returns focus to the text area`() = goToLineTest { harness ->
        field().performTextInput("4")
        field().performKeyInput { pressKey(Key.Escape) }

        onNodeWithTag(EditorPopupTags.GO_TO_LINE).assertDoesNotExist()
        runOnIdle {
            assertFalse(harness.model.overlays.goToLine.value)
            assertTrue(harness.model.goToLineQueries.isEmpty())
            assertEquals(1, harness.model.focusRequests)
        }
    }

    @Test
    fun `focus moving elsewhere dismisses the popup without taking focus back`() = goToLineTest { harness ->
        field().assertIsFocused()

        runOnIdle { harness.textArea.requestFocus() }

        onNodeWithTag(EditorPopupTags.GO_TO_LINE).assertDoesNotExist()
        onNodeWithTag(TEXT_AREA).assertIsFocused()
        runOnIdle {
            assertFalse(harness.model.overlays.goToLine.value)
            assertEquals(0, harness.model.focusRequests)
        }
    }

    @Test
    fun `reopening starts with an empty field`() = goToLineTest { harness ->
        field().performTextInput("4")
        field().performKeyInput { pressKey(Key.Escape) }

        runOnIdle { harness.model.overlays.openGoToLine() }

        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))).assertIsFocused()
    }
}
