package io.karpfen.ide.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineIndexTest {

    @Test
    fun `the empty text has one empty line`() {
        val index = LineIndex("")

        assertEquals(1, index.lineCount)
        assertEquals(0, index.lineStart(0))
        assertEquals(0, index.lineEnd(0))
        assertEquals(0, index.lineOf(0))
        assertEquals(TextPosition.ORIGIN, index.positionOf(0))
        assertEquals(0, index.offsetOf(TextPosition(3, 7)))
        assertEquals(0, index.offsetOf(-1, -1))
    }

    @Test
    fun `a trailing newline yields a final empty line`() {
        val index = LineIndex("ab\n")

        assertEquals(2, index.lineCount)
        assertEquals(TextRange(0, 2), TextRange(index.lineStart(0), index.lineEnd(0)))
        assertEquals(TextRange(3, 3), TextRange(index.lineStart(1), index.lineEnd(1)))
        assertEquals(0, index.lineOf(2), "the newline belongs to the line it terminates")
        assertEquals(1, index.lineOf(3), "the end of the text belongs to the last line")
        assertEquals(TextPosition(1, 0), index.positionOf(3))
    }

    @Test
    fun `a text of only newlines has one empty line more than newlines`() {
        val index = LineIndex("\n\n\n")

        assertEquals(4, index.lineCount)
        for (line in 0 until 4) {
            assertEquals(line, index.lineStart(line))
            assertEquals(line, index.lineEnd(line))
            assertEquals(line, index.lineOf(line))
            assertEquals(TextPosition(line, 0), index.positionOf(line))
        }
    }

    @Test
    fun `a very long line is one line with exact columns`() {
        val length = 1_000_000
        val index = LineIndex("x".repeat(length))

        assertEquals(1, index.lineCount)
        assertEquals(length, index.lineEnd(0))
        assertEquals(TextPosition(0, length - 1), index.positionOf(length - 1))
        assertEquals(TextPosition(0, length), index.positionOf(length))
        assertEquals(length / 2, index.offsetOf(0, length / 2))
        assertEquals(length, index.offsetOf(0, Int.MAX_VALUE), "a huge column clamps without overflow")
    }

    @Test
    fun `offsets of positions are clamped to the line and the text`() {
        val index = LineIndex("type A\n  x\nend")

        assertEquals(9, index.offsetOf(TextPosition(1, 2)))
        assertEquals(10, index.offsetOf(TextPosition(1, 99)), "column past the end of the line")
        assertEquals(14, index.offsetOf(TextPosition(5, 0)), "line past the last line")
        assertEquals(2, index.offsetOf(-3, 2), "negative line clamps to the first line")
        assertEquals(7, index.offsetOf(1, -4), "negative column clamps to the line start")
    }

    @Test
    fun `invalid lines and offsets are rejected`() {
        val index = LineIndex("a\nb")

        assertFailsWith<IllegalArgumentException> { index.lineStart(2) }
        assertFailsWith<IllegalArgumentException> { index.lineEnd(-1) }
        assertFailsWith<IllegalArgumentException> { index.lineOf(4) }
        assertFailsWith<IllegalArgumentException> { index.positionOf(-1) }
    }

    @Test
    fun `every query agrees with a naive scan on random texts`() {
        val random = Random(20261020)
        repeat(300) {
            val text = buildString { repeat(random.nextInt(60)) { append(if (random.nextInt(4) == 0) '\n' else 'a') } }
            val index = LineIndex(text)
            val naiveLines = text.split('\n')

            assertEquals(naiveLines.size, index.lineCount)
            var start = 0
            naiveLines.forEachIndexed { line, content ->
                assertEquals(start, index.lineStart(line))
                assertEquals(start + content.length, index.lineEnd(line))
                start += content.length + 1
            }
            for (offset in 0..text.length) {
                val line = text.substring(0, offset).count { it == '\n' }
                val column = offset - (text.lastIndexOf('\n', offset - 1) + 1)
                assertEquals(TextPosition(line, column), index.positionOf(offset))
                assertEquals(line, index.lineOf(offset))
                assertEquals(offset, index.offsetOf(index.positionOf(offset)), "round trip")
            }
        }
    }

    @Test
    fun `snapshots expose the index through lines and positions`() {
        val snapshot = TextSnapshot("a\nbc\n", 0)

        assertEquals(3, snapshot.lineCount)
        assertEquals("bc", snapshot.lineText(1))
        assertEquals(TextRange(2, 4), snapshot.lineRange(1))
        assertEquals(TextPosition(1, 1), snapshot.positionOf(3))
        assertEquals(3, snapshot.offsetOf(1, 1))
    }
}
