package io.karpfen.ide.ui.editor.model.caret

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries.CharClass
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one word rule (spec §7.1: identifier chars `[A-Za-z0-9_]`, punctuation runs, whitespace). */
class WordBoundariesTest {

    @Test
    fun `identifier characters are exactly A-Z a-z 0-9 and underscore`() {
        val identifier = ('a'..'z') + ('A'..'Z') + ('0'..'9') + '_'
        (0 until 0x250).map(Int::toChar).forEach { char ->
            assertEquals(char in identifier, WordBoundaries.isIdentifierChar(char), "'$char'")
        }
        assertEquals(CharClass.PUNCTUATION, WordBoundaries.classOf('ä'), "non-ASCII letters are not identifier characters")
    }

    @Test
    fun `classes`() {
        assertEquals(CharClass.IDENTIFIER, WordBoundaries.classOf('x'))
        assertEquals(CharClass.WHITESPACE, WordBoundaries.classOf(' '))
        assertEquals(CharClass.WHITESPACE, WordBoundaries.classOf('\t'))
        assertEquals(CharClass.LINE_BREAK, WordBoundaries.classOf('\n'))
        assertEquals(CharClass.PUNCTUATION, WordBoundaries.classOf('$'))
    }

    @Test
    fun `word bounds at the text bounds`() {
        assertEquals(0, WordBoundaries.previousWordStart("abc", 0))
        assertEquals(3, WordBoundaries.nextWordEnd("abc", 3))
        assertEquals(0, WordBoundaries.previousWordStart("", 0))
        assertEquals(0, WordBoundaries.nextWordEnd("", 0))
        assertEquals(TextRange.empty(0), WordBoundaries.wordAt("", 0))
    }

    @Test
    fun `line breaks are runs of their own`() {
        assertEquals(2, WordBoundaries.previousWordStart("a\n\n", 3))
        assertEquals(1, WordBoundaries.previousWordStart("a\n\n", 2))
        assertEquals(2, WordBoundaries.nextWordEnd("a\n\nb", 1))
    }

    @Test
    fun `word movement and word selection agree on random texts`() {
        val random = Random(20261020)
        val alphabet = "ab_1 \t\n.()->\"$"
        repeat(500) {
            val text = String(CharArray(random.nextInt(0, 30)) { alphabet[random.nextInt(alphabet.length)] })
            for (offset in 0..text.length) {
                val start = WordBoundaries.previousWordStart(text, offset)
                val end = WordBoundaries.nextWordEnd(text, offset)
                assertTrue(start <= offset && end >= offset, "'$text' @ $offset")
                assertTrue(offset == 0 || start < offset, "previous word start moves left in '$text' @ $offset")
                assertTrue(offset == text.length || end > offset, "next word end moves right in '$text' @ $offset")
                val sameClass = (start until offset).map { WordBoundaries.classOf(text[it]) }.toSet()
                assertTrue(sameClass.size <= 1, "one run in '$text' @ $offset")
                val word = WordBoundaries.wordAt(text, offset)
                assertTrue('\n' !in text.substring(word.start, word.end), "no line break in a selected word: '$text' @ $offset")
                assertTrue(word.containsInclusive(offset), "the word touches the click: '$text' @ $offset")
            }
        }
    }
}
