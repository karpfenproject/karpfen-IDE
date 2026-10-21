package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange as UiTextRange
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.state.FindBarMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The find/replace bar through the overlay contract (`EditorPopups.Header` with a fake host and the real find model):
 * incremental search with the "n/m" counter, the Aa / W / .* toggles, Enter / Shift+Enter, Escape returning focus,
 * refocusing on a repeated open, and replace (spec §7.2).
 */
@OptIn(ExperimentalTestApi::class)
class FindReplaceBarTest {

    private fun findBarTest(text: String, block: ComposeUiTest.(FakePopupModel) -> Unit) = popupTest(text) { block(it.model) }

    private fun ComposeUiTest.open(model: FakePopupModel, mode: FindBarMode = FindBarMode.FIND) {
        runOnIdle { model.overlays.openFind(mode) }
        waitForIdle()
    }

    private fun ComposeUiTest.selectionOfQueryField(): UiTextRange =
        onNodeWithTag(EditorPopupTags.FIND_QUERY).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    @Test
    fun `the bar is shown only while the find bar is open and takes focus when opened`() = findBarTest("foo bar foo") { model ->
        onNodeWithTag(EditorPopupTags.FIND_BAR).assertDoesNotExist()

        open(model)

        onNodeWithTag(EditorPopupTags.FIND_BAR).assertExists()
        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertIsFocused()
        onNodeWithTag(EditorPopupTags.REPLACE_FIELD).assertDoesNotExist()
    }

    @Test
    fun `typing a query searches incrementally and shows the n of m counter`() = findBarTest("foo bar foo baz foo") { model ->
        open(model)

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")

        runOnIdle {
            assertEquals(FindQuery("foo"), model.find.state.value.query)
            assertEquals(3, model.find.state.value.total)
            assertEquals(TextRange(0, 3), model.caret.primary.selection, "the first match is selected through the model")
        }
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("1/3")
    }

    @Test
    fun `Enter selects the next match and Shift+Enter the previous one`() = findBarTest("foo bar foo baz foo") { model ->
        open(model)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(TextRange(8, 11), model.caret.primary.selection) }
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("2/3")

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        runOnIdle { assertEquals(TextRange(8, 11), model.caret.primary.selection) }
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("2/3")
        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertTextEquals("foo")
        runOnIdle { assertTrue(model.executorLog.all { it == "editor.find.selectMatch" }, "${model.executorLog}") }
    }

    @Test
    fun `the arrow buttons step through the matches and are disabled without matches`() = findBarTest("ab ab") { model ->
        open(model)
        onNodeWithContentDescription("Next Match").assertIsNotEnabled()
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("ab")

        onNodeWithContentDescription("Next Match").assertIsEnabled().performClick()
        runOnIdle { assertEquals(TextRange(3, 5), model.caret.primary.selection) }
        onNodeWithContentDescription("Previous Match").performClick()
        runOnIdle { assertEquals(TextRange(0, 2), model.caret.primary.selection) }
        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertIsFocused()
    }

    @Test
    fun `the toggles switch case sensitivity, whole words and regular expressions`() = findBarTest("Foo foo food") { model ->
        open(model)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("1/3")
        onNodeWithContentDescription("Match Case").assertIsOff()

        onNodeWithContentDescription("Match Case").performClick()
        onNodeWithContentDescription("Match Case").assertIsOn()
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("1/2")

        onNodeWithContentDescription("Whole Words").performClick()
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("1/1")

        onNodeWithContentDescription("Regular Expression").performClick()
        runOnIdle {
            assertEquals(FindQuery("foo", caseSensitive = true, wholeWord = true, regex = true), model.find.state.value.query)
        }
        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertIsFocused()
    }

    @Test
    fun `an invalid regular expression shows its error instead of the counter`() = findBarTest("a(b") { model ->
        open(model)
        onNodeWithContentDescription("Regular Expression").performClick()

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("(")

        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextContains("Unclosed group", substring = true)
        runOnIdle { assertEquals(0, model.find.state.value.total) }
    }

    @Test
    fun `Escape closes the bar and returns focus to the text area`() = findBarTest("foo") { model ->
        open(model)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")

        onNodeWithTag(EditorPopupTags.FIND_QUERY).performKeyInput { pressKey(Key.Escape) }

        onNodeWithTag(EditorPopupTags.FIND_BAR).assertDoesNotExist()
        runOnIdle {
            assertNull(model.overlays.findBar.value)
            assertEquals(1, model.focusRequests)
            assertEquals("foo", model.find.state.value.query.text, "closing keeps the query")
        }
    }

    @Test
    fun `the close button closes the bar and returns focus`() = findBarTest("foo") { model ->
        open(model, FindBarMode.REPLACE)

        onNodeWithContentDescription("Close").performClick()

        onNodeWithTag(EditorPopupTags.FIND_BAR).assertDoesNotExist()
        runOnIdle { assertEquals(1, model.focusRequests) }
    }

    @Test
    fun `a second openFind in the same mode refocuses the query field and selects the query`() = findBarTest("foo bar") { model ->
        open(model, FindBarMode.REPLACE)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")
        onNodeWithTag(EditorPopupTags.REPLACE_FIELD).performClick()
        onNodeWithTag(EditorPopupTags.REPLACE_FIELD).assertIsFocused()

        open(model, FindBarMode.REPLACE)

        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertIsFocused()
        assertEquals(UiTextRange(0, 3), selectionOfQueryField())
        runOnIdle { assertEquals(FindBarMode.REPLACE, model.overlays.findBar.value) }
    }

    @Test
    fun `switching from find to replace keeps the query and shows the replace row`() = findBarTest("foo") { model ->
        open(model)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("fo")

        open(model, FindBarMode.REPLACE)

        onNodeWithTag(EditorPopupTags.REPLACE_FIELD).assertExists()
        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertTextEquals("fo").assertIsFocused()
    }

    @Test
    fun `a query seeded by the editor before openFind appears selected in the field`() = findBarTest("foo bar") { model ->
        runOnIdle {
            model.find.setQuery(FindQuery("bar"))
            model.overlays.openFind(FindBarMode.FIND)
        }

        onNodeWithTag(EditorPopupTags.FIND_QUERY).assertTextEquals("bar").assertIsFocused()
        assertEquals(UiTextRange(0, 3), selectionOfQueryField())
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("1/1")
    }

    @Test
    fun `replace and replace all edit the text through the find model`() = findBarTest("foo bar foo baz foo") { model ->
        open(model, FindBarMode.REPLACE)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")
        onNodeWithTag(EditorPopupTags.REPLACE_FIELD).performTextInput("qux")

        onNodeWithContentDescription("Replace").performClick()
        runOnIdle { assertEquals("qux bar foo baz foo", model.plain) }

        onNodeWithTag(EditorPopupTags.REPLACE_FIELD).performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("qux bar qux baz foo", model.plain) }

        onNodeWithContentDescription("Replace All").performClick()
        runOnIdle { assertEquals("qux bar qux baz qux", model.plain) }
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("0/0")
        onNodeWithContentDescription("Replace All").assertIsNotEnabled()
    }

    @Test
    fun `the replacement survives a new composition of the editor, as after switching tabs`() = runComposeUiTest {
        FakePopupModel("foo bar foo").use { model ->
            val host = FakeOverlayHost(model)
            var composition by mutableStateOf(0)
            setContent {
                // The workbench composes CodeEditor in key(activePath), so switching tabs disposes the bar's fields.
                key(composition) { Column { EditorPopups().Header(host) } }
            }
            open(model, FindBarMode.REPLACE)
            onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")
            onNodeWithTag(EditorPopupTags.REPLACE_FIELD).performTextInput("qux")

            runOnIdle { composition++ }

            onNodeWithTag(EditorPopupTags.REPLACE_FIELD).assertTextEquals("qux")
            onNodeWithContentDescription("Replace All").performClick()
            runOnIdle { assertEquals("qux bar qux", model.plain, "the matches were replaced, not deleted") }
        }
    }

    @Test
    fun `the counter follows edits of the text`() = findBarTest("foo") { model ->
        open(model)
        onNodeWithTag(EditorPopupTags.FIND_QUERY).performTextInput("foo")
        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextEquals("1/1")

        runOnIdle { model.text.replaceAll("foo foo", EditOrigin.External) }

        onNodeWithTag(EditorPopupTags.FIND_STATUS).assertTextContains("/2", substring = true)
    }

    @Test
    fun `a bar composed while already open does not take focus from the text area`() = runComposeUiTest {
        FakePopupModel("foo").use { model ->
            model.overlays.openFind(FindBarMode.FIND)

            setPopups(FakeOverlayHost(model))

            onNodeWithTag(EditorPopupTags.FIND_BAR).assertExists()
            onNodeWithTag(EditorPopupTags.FIND_QUERY).assertIsNotFocused()
        }
    }
}
