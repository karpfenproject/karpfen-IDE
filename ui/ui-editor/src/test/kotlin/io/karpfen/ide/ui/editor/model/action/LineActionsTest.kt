package io.karpfen.ide.ui.editor.model.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Duplicate, move line up/down, indent and outdent. */
class LineActionsTest {

    private fun after(marked: String, actionId: String): String = TestEditor(marked).apply { run(actionId) }.marked()

    @Test
    fun `duplicate copies the line below and moves the caret into the copy`() {
        assertEquals("a\nbc\nb|c\nd", after("a\nb|c\nd", EditorActionIds.DUPLICATE))
        assertEquals("a\nb\nb|", after("a\nb|", EditorActionIds.DUPLICATE), "the last line")
        assertEquals("\n|", after("|", EditorActionIds.DUPLICATE), "the empty text")
    }

    @Test
    fun `duplicate with a selection copies the selection and selects the copy`() {
        assertEquals("xab^ab|y", after("x^ab|y", EditorActionIds.DUPLICATE))
        assertEquals("xab|ab^y", after("x|ab^y", EditorActionIds.DUPLICATE), "the direction is kept")
        assertEquals("a\nb^a\nb|\nc", after("^a\nb|\nc", EditorActionIds.DUPLICATE))
    }

    @Test
    fun `move line up and down swap with the neighbour and carry the caret`() {
        assertEquals("b|b\na\nc", after("a\nb|b\nc", EditorActionIds.MOVE_LINE_UP))
        assertEquals("a\nc\nb|b", after("a\nb|b\nc", EditorActionIds.MOVE_LINE_DOWN))
    }

    @Test
    fun `move line does nothing at the text bounds`() {
        val top = TestEditor("a|\nb")
        assertFalse(top.run(EditorActionIds.MOVE_LINE_UP))
        val bottom = TestEditor("a\nb|")
        assertFalse(bottom.run(EditorActionIds.MOVE_LINE_DOWN))
        assertFalse(TestEditor("|").run(EditorActionIds.MOVE_LINE_DOWN))
        assertEquals("a|\nb", top.marked())
    }

    @Test
    fun `move line moves every line of a selection`() {
        assertEquals("^bb\nc|c\na\nd", after("a\n^bb\nc|c\nd", EditorActionIds.MOVE_LINE_UP))
        assertEquals("b\n^a\n|c", after("^a\n|b\nc", EditorActionIds.MOVE_LINE_DOWN), "a selection ending at column 0 spares that line")
        assertEquals("b\n^a|", after("^a\n|b", EditorActionIds.MOVE_LINE_DOWN), "the caret is clamped at the new end")
    }

    @Test
    fun `tab inserts spaces up to the next 4-column stop`() {
        assertEquals("    |x", after("|x", EditorActionIds.INDENT))
        assertEquals("ab  |c", after("ab|c", EditorActionIds.INDENT))
        assertEquals("    |x", after("  |x", EditorActionIds.INDENT))
        assertEquals("\t    |x", after("\t|x", EditorActionIds.INDENT), "a tab counts to its stop")
        assertEquals("a   |d", after("a^bc|d", EditorActionIds.INDENT), "a selection inside one line is replaced")
    }

    @Test
    fun `tab with a multi-line selection indents every non-empty line and keeps them selected`() {
        assertEquals("^    a\n    b\n|c", after("^a\nb\n|c", EditorActionIds.INDENT))
        assertEquals("^    a\n\n    b|", after("^a\n\nb|", EditorActionIds.INDENT), "empty lines stay empty")
        assertEquals("    a|\n    b^", after("a|\nb^", EditorActionIds.INDENT), "the direction is kept")
        assertFalse(TestEditor("^\n\n|").run(EditorActionIds.INDENT), "only empty lines")
    }

    @Test
    fun `shift tab removes one indentation level`() {
        assertEquals("    |x", after("        |x", EditorActionIds.OUTDENT))
        assertEquals("    x|", after("      x|", EditorActionIds.OUTDENT), "back to the previous stop")
        assertEquals("|x", after("\t|x", EditorActionIds.OUTDENT))
        assertEquals("|x", after("  |  x", EditorActionIds.OUTDENT), "a caret inside the removed indentation")
        assertFalse(TestEditor("x|").run(EditorActionIds.OUTDENT))
        assertEquals("^a\nb\nc|", after("^    a\n  b\nc|", EditorActionIds.OUTDENT))
    }

    @Test
    fun `line actions are one undo step each and restore the carets`() {
        val ids = listOf(
            EditorActionIds.DUPLICATE, EditorActionIds.MOVE_LINE_UP, EditorActionIds.MOVE_LINE_DOWN,
            EditorActionIds.INDENT, EditorActionIds.OUTDENT,
        )
        for (id in ids) {
            val start = "a\n    ^b\n    c|\nd"
            val editor = TestEditor(start)
            editor.run(id)
            val changed = editor.marked()
            editor.run(EditorActionIds.UNDO)
            assertEquals(start, editor.marked(), "undo of $id")
            editor.run(EditorActionIds.REDO)
            assertEquals(changed, editor.marked(), "redo of $id")
        }
    }
}
