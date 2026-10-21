package io.karpfen.ide.ui.editor.popup

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.HoverContent
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The popups' intents on the editor and the popup key policy, Compose-free over the fake model. */
class PopupIntentsTest {

    private fun intentsTest(text: String, caretAt: Int = 0, block: (FakePopupModel, PopupIntents) -> Unit) =
        FakePopupModel(text, caretAt).use { model -> block(model, PopupIntents(model)) }

    @Test
    fun `find intents drive the find model`() = intentsTest("ab ab ab") { model, intents ->
        intents.findTextChanged("ab")
        assertEquals(TextRange(0, 2), model.caret.primary.selection)
        intents.toggleFindOption(FindOption.WHOLE_WORD)
        assertEquals(FindQuery("ab", wholeWord = true), model.find.query.value, "a toggle keeps the text")
        intents.findTextChanged("ab")
        assertEquals(FindQuery("ab", wholeWord = true), model.find.query.value, "the text keeps the toggles")

        intents.findNext()
        assertEquals(TextRange(3, 5), model.caret.primary.selection)
        intents.findPrevious()
        assertEquals(TextRange(0, 2), model.caret.primary.selection)

        intents.replace("x")
        assertEquals("x ab ab", model.plain)
        intents.replaceAll("y")
        assertEquals("x y y", model.plain)
        assertEquals(0, model.focusRequests)
    }

    @Test
    fun `closing the find bar keeps the query and returns focus`() = intentsTest("ab") { model, intents ->
        model.overlays.openFind(FindBarMode.FIND)
        intents.findTextChanged("ab")

        intents.closeFind()

        assertNull(model.overlays.findBar.value)
        assertEquals("ab", model.find.query.value.text)
        assertEquals(1, model.focusRequests)
    }

    @Test
    fun `completion intents select and accept through the model`() = intentsTest("pr", caretAt = 2) { model, intents ->
        model.completionItems = completionItems(listOf("print", "prop"))
        model.completion.open()

        intents.selectCompletion(1)
        assertEquals(1, model.completion.state.value?.selectedIndex)
        intents.acceptCompletion()

        assertEquals("prop", model.plain)
        assertNull(model.completion.state.value)
        assertEquals(0, model.focusRequests, "the text area kept focus")
    }

    @Test
    fun `a completion list that cannot be shown is closed`() = intentsTest("pr", caretAt = 2) { model, intents ->
        model.completionItems = completionItems(listOf("print", "prop"))
        model.completion.open()

        intents.completionHidden()

        assertNull(model.completion.state.value)
    }

    @Test
    fun `go-to-line intents go, cancel with focus return, and dismiss without`() = intentsTest("a\nb\nc") { model, intents ->
        model.overlays.openGoToLine()
        intents.goToLine(GoToLineQuery(3))
        assertEquals(listOf(GoToLineQuery(3)), model.goToLineQueries)
        assertEquals(4, model.caret.primary.position)

        model.overlays.openGoToLine()
        intents.cancelGoToLine()
        assertFalse(model.overlays.goToLine.value)
        assertEquals(2, model.focusRequests, "goToLine and cancel each return focus")

        model.overlays.openGoToLine()
        intents.dismissGoToLine()
        assertFalse(model.overlays.goToLine.value)
        assertEquals(2, model.focusRequests, "dismissal leaves focus where it went")
    }

    @Test
    fun `dismissing the hover hides it without touching focus`() = intentsTest("x") { model, intents ->
        val diagnostic = Diagnostic(TextRange(0, 1), Severity.ERROR, "bad", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
        model.overlays.showHover(HoverContent(TextRange(0, 1), listOf(diagnostic), null))

        intents.dismissHover()

        assertNull(model.overlays.hover.value)
        assertEquals(0, model.focusRequests)
    }

    @Test
    fun `the pointer on the hover tooltip is reported to the editor`() = intentsTest("ab") { model, intents ->
        intents.pointerOverTooltip(true)
        intents.pointerOverTooltip(false)

        assertEquals(listOf(true, false), model.tooltipPointer)
    }

    @Test
    fun `a context-menu entry closes the menu, runs its action by id and returns focus`() = intentsTest("state A") { model, intents ->
        model.overlays.openContextMenu(ContextMenuRequest(0, 1f, 1f))

        intents.runContextAction(EditorActionIds.SELECT_ALL)

        assertNull(model.overlays.contextMenu.value)
        assertEquals(listOf(EditorActionIds.SELECT_ALL), model.executed)
        assertEquals(TextRange(0, 7), model.caret.primary.selection)
        assertEquals(1, model.focusRequests)
    }

    @Test
    fun `dismissing the context menu returns focus without running anything`() = intentsTest("state A") { model, intents ->
        model.overlays.openContextMenu(ContextMenuRequest(0, 1f, 1f))

        intents.dismissContextMenu()

        assertNull(model.overlays.contextMenu.value)
        assertTrue(model.executed.isEmpty())
        assertEquals(1, model.focusRequests)
    }

    @Test
    fun `popup keys are Enter, Shift+Enter and Escape without other modifiers`() {
        assertEquals(PopupKey.CONFIRM, PopupKeys.of(KeyStroke(KeyCode.ENTER)))
        assertEquals(PopupKey.CONFIRM_BACKWARD, PopupKeys.of(KeyStroke(KeyCode.ENTER, setOf(KeyModifier.SHIFT))))
        assertEquals(PopupKey.CANCEL, PopupKeys.of(KeyStroke(KeyCode.ESCAPE)))
        assertNull(PopupKeys.of(KeyStroke(KeyCode.ENTER, setOf(KeyModifier.META))))
        assertNull(PopupKeys.of(KeyStroke(KeyCode.ESCAPE, setOf(KeyModifier.SHIFT))))
        assertNull(PopupKeys.of(KeyStroke(KeyCode.TAB)))
        assertNull(PopupKeys.of(null), "key-up events and unmapped keys")
    }
}
