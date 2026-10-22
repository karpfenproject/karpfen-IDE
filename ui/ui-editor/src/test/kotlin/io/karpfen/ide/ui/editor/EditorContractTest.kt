package io.karpfen.ide.ui.editor

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.EditorActionTitles
import io.karpfen.ide.ui.editor.model.action.defaultEditorActions
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.editor.model.caret.DEFAULT_TAB_WIDTH
import io.karpfen.ide.ui.editor.model.caret.TabStops
import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.popup.EditorPopups
import io.karpfen.ide.ui.editor.state.ContextMenuRequest
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.editor.state.EditorOverlayState
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.state.HoverContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the public values and wiring of ui-editor that the workbench relies on: go-to-line input, carets, the default
 * actions with their ids and shared titles, the overlay state, and tab stops with the default tab width.
 */
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
            EditorActionIds.FIND_NEXT, EditorActionIds.FIND_PREVIOUS,
        ).forEach { assertTrue(it in ids, "missing $it") }
    }

    @Test
    fun `every editor action id has one shared title`() {
        val declaredIds = EditorActionIds::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }

        assertTrue(declaredIds.size > 40, "reflection found only $declaredIds")
        assertEquals(declaredIds.toSet(), EditorActionTitles.titles.keys)
        defaultEditorActions().forEach { EditorActionTitles.of(it.id) }
        assertEquals("Go to Definition", EditorPopups.DEFAULT_CONTEXT_MENU.single { it.actionId == EditorActionIds.GO_TO_DEFINITION }.title)
    }

    @Test
    fun `the overlay state tracks open overlays`() {
        val overlays = EditorOverlayState()
        val hover = HoverContent(
            TextRange(0, 1),
            listOf(Diagnostic(TextRange(0, 1), Severity.ERROR, "x", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)),
            hover = null,
        )

        overlays.openFind(FindBarMode.REPLACE)
        overlays.openFind(FindBarMode.REPLACE)
        assertEquals(2L, overlays.findFocusRequests.value, "a repeated Cmd/Ctrl+F requests focus again")
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

    @Test
    fun `tab stops convert between columns and display columns`() {
        val stops = TabStops(4)

        assertEquals(4, stops.displayColumn("\tab", 1))
        assertEquals(6, stops.displayColumn("\tab", 3))
        assertEquals(4, stops.displayColumn("a\tb", 2), "a tab advances to the next stop")
        assertEquals(6, stops.displayColumn("\tab", 99), "clamped to the line")
        assertEquals(1, stops.columnAt("\tab", 4))
        assertEquals(0, stops.columnAt("\tab", 2), "a tie inside a tab goes left")
        assertEquals(1, stops.columnAt("\tab", 3), "the nearer bound of a tab")
        assertEquals(3, stops.columnAt("\tab", 99))
        assertEquals(0, stops.columnAt("ab", -1))
        val pair = "x\uD83D\uDE00y"
        assertEquals(2, stops.displayColumn(pair, 3), "a surrogate pair is one display column")
        assertEquals(3, stops.columnAt(pair, 2), "never inside a surrogate pair")
        assertEquals(2, TabStops(2).displayColumn("\t", 1))
        assertFailsWith<IllegalArgumentException> { TabStops(0) }
    }
}
