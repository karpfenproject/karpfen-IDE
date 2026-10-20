package io.karpfen.ide.ui.editor

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.defaultEditorActions
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.caret.DEFAULT_TAB_WIDTH
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.editor.state.EditorOverlayController
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.HoverContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract test of the complete ui-editor values and wiring (no phase-1 implementation code is executed). */
class EditorContractTest {

    @Test
    fun `go-to-line input is parsed as one-based line and optional column`() {
        assertEquals(GoToLineQuery(12), GoToLineQuery.parse(" 12 "))
        assertEquals(GoToLineQuery(3, 7), GoToLineQuery.parse("3:7"))
        assertNull(GoToLineQuery.parse("0"))
        assertNull(GoToLineQuery.parse("3:"))
        assertNull(GoToLineQuery.parse("a"))
    }

    @Test
    fun `carets know their selection`() {
        val caret = Caret(anchor = 9, position = 4)

        assertTrue(caret.hasSelection)
        assertEquals(TextRange(4, 9), caret.selection)
        assertEquals(4, caret.toMemento().caret)
        assertFalse(Caret.at(3).hasSelection)
    }

    @Test
    fun `the default actions have unique ids covering the menu and context-menu commands`() {
        val ids = defaultEditorActions().map { it.id }

        assertEquals(ids.size, ids.toSet().size, "duplicate action ids")
        listOf(
            EditorActionIds.UNDO, EditorActionIds.REDO, EditorActionIds.CUT, EditorActionIds.COPY, EditorActionIds.PASTE,
            EditorActionIds.SELECT_ALL, EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.TOGGLE_COMMENT,
            EditorActionIds.DUPLICATE, EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION,
        ).forEach { assertTrue(it in ids, "missing $it") }
    }

    @Test
    fun `the overlay controller tracks open overlays`() {
        val overlays = EditorOverlayController()
        val hover = HoverContent(
            TextRange(0, 1),
            listOf(Diagnostic(TextRange(0, 1), Severity.ERROR, "x", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)),
            hover = null,
        )

        overlays.openFind(FindBarMode.REPLACE)
        overlays.showHover(hover)
        overlays.openContextMenu(ContextMenuRequest(0, 1f, 2f))

        assertEquals(FindBarMode.REPLACE, overlays.findBar.value)
        assertNull(overlays.hover.value, "the context menu hides the hover")
        overlays.openGoToLine()
        assertTrue(overlays.goToLine.value)
        assertNull(overlays.contextMenu.value)
        overlays.closeAll()
        assertNull(overlays.findBar.value)
        assertFailsWith<IllegalArgumentException> { HoverContent(TextRange(0, 1), emptyList(), null) }
    }

    @Test
    fun `the caret model's default tab width matches the editor settings`() {
        assertEquals(EditorSettings().tabDisplayWidth, DEFAULT_TAB_WIDTH)
    }
}
