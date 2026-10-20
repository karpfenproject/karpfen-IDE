package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.ui.editor.model.action.TestEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every [CaretMovement] with and without selection extension (spec §7.1). */
class CaretMovementTest {

    private fun moved(marked: String, movement: CaretMovement, extend: Boolean = false, pageLines: Int = 1): String {
        val editor = TestEditor(marked)
        editor.caret.move(movement, extend, pageLines)
        return editor.marked()
    }

    @Test
    fun `left and right step one character and stop at the text bounds`() {
        assertEquals("a|bc", moved("ab|c", CaretMovement.LEFT))
        assertEquals("abc|", moved("ab|c", CaretMovement.RIGHT))
        assertEquals("|abc", moved("|abc", CaretMovement.LEFT))
        assertEquals("abc|", moved("abc|", CaretMovement.RIGHT))
        assertEquals("a\n|b", moved("a|\nb", CaretMovement.RIGHT), "a line break is one character")
    }

    @Test
    fun `left and right step over a surrogate pair as one character`() {
        val emoji = "😀"
        assertEquals("a|${emoji}b", moved("a$emoji|b", CaretMovement.LEFT))
        assertEquals("a$emoji|b", moved("a|${emoji}b", CaretMovement.RIGHT))
    }

    @Test
    fun `left and right collapse a selection to its start and end`() {
        assertEquals("a|bcd", moved("a^bc|d", CaretMovement.LEFT))
        assertEquals("abc|d", moved("a^bc|d", CaretMovement.RIGHT))
        assertEquals("a|bcd", moved("a|bc^d", CaretMovement.LEFT))
        assertEquals("abc|d", moved("a|bc^d", CaretMovement.RIGHT))
    }

    @Test
    fun `shift movements extend the selection from the anchor`() {
        assertEquals("a^b|cd", moved("a^|bcd", CaretMovement.RIGHT, extend = true))
        assertEquals("a^bc|d", moved("a^b|cd", CaretMovement.RIGHT, extend = true))
        assertEquals("a|b^cd", moved("ab^|cd", CaretMovement.LEFT, extend = true))
        assertEquals("ab|cd", moved("ab^c|d", CaretMovement.LEFT, extend = true), "a selection shrunk to nothing is no selection")
        assertEquals("^ab\ncd|", moved("|ab\ncd", CaretMovement.DOCUMENT_END, extend = true))
    }

    @Test
    fun `word movement stops at identifier, punctuation and whitespace runs`() {
        val text = "foo.bar(x_1)  baz"
        val stops = mutableListOf<Int>()
        val editor = TestEditor("|$text")
        repeat(9) {
            editor.caret.move(CaretMovement.WORD_RIGHT, extendSelection = false)
            stops += editor.caret.primary.position
        }
        assertEquals(listOf(3, 4, 7, 8, 11, 12, 14, 17, 17), stops)
        val back = mutableListOf<Int>()
        repeat(9) {
            editor.caret.move(CaretMovement.WORD_LEFT, extendSelection = false)
            back += editor.caret.primary.position
        }
        assertEquals(listOf(14, 12, 11, 8, 7, 4, 3, 0, 0), back)
    }

    @Test
    fun `word movement stops at every line break`() {
        assertEquals("foo|\n    bar", moved("foo\n|    bar", CaretMovement.WORD_LEFT))
        assertEquals("foo\n|    bar", moved("foo|\n    bar", CaretMovement.WORD_RIGHT))
        assertEquals("foo\n    |bar", moved("foo\n|    bar", CaretMovement.WORD_RIGHT))
        assertEquals("^foo|.bar", moved("^|foo.bar", CaretMovement.WORD_RIGHT, extend = true))
    }

    @Test
    fun `smart home goes to the first non-whitespace character, then to column 0`() {
        assertEquals("ab\n    |foo", moved("ab\n    fo|o", CaretMovement.LINE_START))
        assertEquals("ab\n|    foo", moved("ab\n    |foo", CaretMovement.LINE_START))
        assertEquals("ab\n    |foo", moved("ab\n|    foo", CaretMovement.LINE_START))
        assertEquals("ab\n    |foo", moved("ab\n  |  foo", CaretMovement.LINE_START), "inside the indentation: to the text")
        assertEquals("|\t", moved("\t|", CaretMovement.LINE_START), "a blank line: its end, then column 0")
        assertEquals("ab\n^    |foo", moved("ab\n^|    foo", CaretMovement.LINE_START, extend = true))
    }

    @Test
    fun `line end and document bounds`() {
        assertEquals("ab|\ncd", moved("|ab\ncd", CaretMovement.LINE_END))
        assertEquals("ab\ncd|", moved("ab\n|cd", CaretMovement.LINE_END))
        assertEquals("|ab\ncd", moved("ab\nc|d", CaretMovement.DOCUMENT_START))
        assertEquals("ab\ncd|", moved("a|b\ncd", CaretMovement.DOCUMENT_END))
    }

    @Test
    fun `up and down keep the preferred column over shorter lines`() {
        val editor = TestEditor("abcdef|\nab\nabcdefgh")
        editor.caret.move(CaretMovement.DOWN, extendSelection = false)
        assertEquals("abcdef\nab|\nabcdefgh", editor.marked())
        assertEquals(6, editor.caret.primary.preferredColumn)
        editor.caret.move(CaretMovement.DOWN, extendSelection = false)
        assertEquals("abcdef\nab\nabcdef|gh", editor.marked())
        editor.caret.move(CaretMovement.UP, extendSelection = false)
        editor.caret.move(CaretMovement.UP, extendSelection = false)
        assertEquals("abcdef|\nab\nabcdefgh", editor.marked())
    }

    @Test
    fun `horizontal movements forget the preferred column`() {
        val editor = TestEditor("abcdef|\nab\nabcdefgh")
        editor.caret.move(CaretMovement.DOWN, extendSelection = false)
        editor.caret.move(CaretMovement.LEFT, extendSelection = false)
        assertNull(editor.caret.primary.preferredColumn)
        editor.caret.move(CaretMovement.DOWN, extendSelection = false)
        assertEquals("abcdef\nab\na|bcdefgh", editor.marked())
    }

    @Test
    fun `up on the first line goes to the document start, down on the last line to its end`() {
        assertEquals("|abc\nde", moved("ab|c\nde", CaretMovement.UP))
        assertEquals("abc\nde|", moved("abc\n|de", CaretMovement.DOWN))
        val editor = TestEditor("ab|c\ndefg")
        editor.caret.move(CaretMovement.UP, extendSelection = false)
        editor.caret.move(CaretMovement.DOWN, extendSelection = false)
        assertEquals("abc\nde|fg", editor.marked(), "the preferred column (2) survives the trip to the document start")
    }

    @Test
    fun `shift up and down extend the selection`() {
        assertEquals("ab^c\nde|f", moved("ab^|c\ndef", CaretMovement.DOWN, extend = true))
        assertEquals("ab|c\nde^f", moved("abc\nde^|f", CaretMovement.UP, extend = true))
    }

    @Test
    fun `page up and down move by the page size`() {
        val lines = (0 until 30).joinToString("\n") { "line$it" }
        val editor = TestEditor("|$lines")
        editor.caret.move(CaretMovement.PAGE_DOWN, extendSelection = false, pageLines = 10)
        assertEquals(10, editor.text.snapshot.value.positionOf(editor.caret.primary.position).line)
        editor.caret.move(CaretMovement.PAGE_DOWN, extendSelection = true, pageLines = 25)
        assertEquals(29, editor.text.snapshot.value.positionOf(editor.caret.primary.position).line, "clamped to the last line")
        assertEquals(editor.text.snapshot.value.lineIndex.lineStart(10), editor.caret.primary.anchor)
        editor.caret.move(CaretMovement.PAGE_UP, extendSelection = false, pageLines = 10)
        assertEquals(19, editor.text.snapshot.value.positionOf(editor.caret.primary.position).line)
        editor.caret.move(CaretMovement.PAGE_UP, extendSelection = false, pageLines = 0)
        assertEquals(18, editor.text.snapshot.value.positionOf(editor.caret.primary.position).line, "a page is at least one line")
    }

    @Test
    fun `movements on the empty text stay at 0`() {
        CaretMovement.entries.forEach { movement ->
            assertEquals("|", moved("|", movement), "$movement")
            assertEquals("|", moved("|", movement, extend = true), "$movement with Shift")
        }
    }
}
