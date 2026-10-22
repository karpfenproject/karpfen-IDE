package io.karpfen.ide.ui.editor.model.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Backspace (smart unindent, pair deletion), Delete, word and line deletion. */
class DeletionActionsTest {

    private fun after(marked: String, actionId: String): String = TestEditor(marked).apply { run(actionId) }.marked()

    @Test
    fun `backspace and delete remove one character or the selection`() {
        assertEquals("a|c", after("ab|c", EditorActionIds.BACKSPACE))
        assertEquals("ab|", after("ab|c", EditorActionIds.DELETE))
        assertEquals("a|d", after("a^bc|d", EditorActionIds.BACKSPACE))
        assertEquals("a|d", after("a|bc^d", EditorActionIds.DELETE))
        assertEquals("ab|cd", after("ab\n|cd", EditorActionIds.BACKSPACE), "joins lines")
        assertEquals("ab|cd", after("ab|\ncd", EditorActionIds.DELETE))
    }

    @Test
    fun `backspace and delete do nothing at the text bounds`() {
        val start = TestEditor("|ab")
        assertFalse(start.run(EditorActionIds.BACKSPACE))
        val end = TestEditor("ab|")
        assertFalse(end.run(EditorActionIds.DELETE))
        assertEquals(0, start.text.snapshot.value.version + end.text.snapshot.value.version)
    }

    @Test
    fun `surrogate pairs are deleted as a whole`() {
        val emoji = "😀"
        assertEquals("a|b", after("a$emoji|b", EditorActionIds.BACKSPACE))
        assertEquals("a|b", after("a|${emoji}b", EditorActionIds.DELETE))
    }

    @Test
    fun `backspace in leading whitespace unindents to the previous 4-column stop`() {
        assertEquals("    |x", after("        |x", EditorActionIds.BACKSPACE))
        assertEquals("    |x", after("      |x", EditorActionIds.BACKSPACE))
        assertEquals("|x", after("   |x", EditorActionIds.BACKSPACE))
        assertEquals("    |    x", after("        |    x", EditorActionIds.BACKSPACE), "text after the caret does not matter")
        assertEquals("\t|x", after("\t  |x", EditorActionIds.BACKSPACE), "spaces after a tab: back to the tab's stop")
        assertEquals("|x", after("\t|x", EditorActionIds.BACKSPACE), "a tab is one character")
        assertEquals("a   |x", after("a    |x", EditorActionIds.BACKSPACE), "after text: one character")
    }

    @Test
    fun `backspace between an auto-closed pair deletes both`() {
        val editor = TestEditor("f|")
        editor.type("(")
        editor.run(EditorActionIds.BACKSPACE)
        assertEquals("f|", editor.marked())

        val quote = TestEditor("x = |")
        quote.type("\"")
        quote.run(EditorActionIds.BACKSPACE)
        assertEquals("x = |", quote.marked())
    }

    @Test
    fun `backspace inside a user-typed pair deletes one character`() {
        val editor = TestEditor("f|x")
        editor.type("(")
        editor.type(")")
        editor.caret.moveTo(2)
        editor.run(EditorActionIds.BACKSPACE)
        assertEquals("f|)x", editor.marked())
        assertEquals("f|)", after("f(|)", EditorActionIds.BACKSPACE), "an untracked pair")
    }

    @Test
    fun `backspace after typing inside an auto-closed pair deletes one character`() {
        val editor = TestEditor("f|")
        editor.type("(")
        editor.type("a")
        editor.run(EditorActionIds.BACKSPACE)
        assertEquals("f(|)", editor.marked())
        editor.run(EditorActionIds.BACKSPACE)
        assertEquals("f|", editor.marked(), "the pair is still tracked")
    }

    @Test
    fun `delete word left and right use the word rule`() {
        assertEquals("foo.|", after("foo.bar|", EditorActionIds.DELETE_WORD_LEFT))
        assertEquals("foo|", after("foo.|", EditorActionIds.DELETE_WORD_LEFT))
        assertEquals("foo|", after("foo   |", EditorActionIds.DELETE_WORD_LEFT), "a whitespace run")
        assertEquals("foo |", after("foo |  ", EditorActionIds.DELETE_WORD_RIGHT))
        assertEquals("|.bar", after("|foo.bar", EditorActionIds.DELETE_WORD_RIGHT))
        assertEquals("a|", after("a\n|", EditorActionIds.DELETE_WORD_LEFT), "a line break is a word of its own")
        assertEquals("a|b", after("a^xyz|b", EditorActionIds.DELETE_WORD_LEFT), "a selection is deleted first")
        assertFalse(TestEditor("|x").run(EditorActionIds.DELETE_WORD_LEFT))
        assertFalse(TestEditor("x|").run(EditorActionIds.DELETE_WORD_RIGHT))
    }

    @Test
    fun `delete line removes the caret's line and keeps the column`() {
        assertEquals("one\nth|ree", after("one\ntw|o\nthree", EditorActionIds.DELETE_LINE))
        assertEquals("one\ntw|o", after("one\ntwo\nth|ree", EditorActionIds.DELETE_LINE), "the last line takes its line break before")
        assertEquals("|", after("on|ly", EditorActionIds.DELETE_LINE))
        assertEquals("one\n|two", after("one\ntwo\n|", EditorActionIds.DELETE_LINE), "an empty last line")
    }

    @Test
    fun `delete line removes every line of a selection`() {
        assertEquals("a\nd|", after("a\nb^b\ncc|\nd", EditorActionIds.DELETE_LINE))
        assertEquals("a\n|cc\nd", after("a\n^bb\n|cc\nd", EditorActionIds.DELETE_LINE), "a selection ending at column 0 spares that line")
    }

    @Test
    fun `deletions are one undo step each and restore the caret`() {
        val ids = listOf(EditorActionIds.DELETE_WORD_LEFT, EditorActionIds.DELETE_WORD_RIGHT, EditorActionIds.DELETE_LINE)
        for (id in ids) {
            val editor = TestEditor("alpha be|ta\ngamma")
            editor.run(id)
            editor.run(EditorActionIds.UNDO)
            assertEquals("alpha be|ta\ngamma", editor.marked(), id)
        }
    }

    @Test
    fun `typed backspaces coalesce`() {
        val editor = TestEditor("abcd|")
        repeat(3) { editor.run(EditorActionIds.BACKSPACE) }
        editor.run(EditorActionIds.UNDO)
        assertEquals("abcd|", editor.marked())
    }
}
