/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The helpers behind the line actions: [EditShift], [LineBlock], [Indentation] and [StringLiterals]. */
class EditGeometryTest {

    @Test
    fun `edit shift moves offsets over insertions, deletions and replacements`() {
        val shift = EditShift(listOf(TextEdit.insert(2, "xx"), TextEdit.delete(5, 7), TextEdit.replace(TextRange(9, 10), "abc")))
        assertEquals(1, shift.offset(1, Bias.RIGHT))
        assertEquals(2, shift.offset(2, Bias.LEFT), "at an insertion: LEFT stays")
        assertEquals(4, shift.offset(2, Bias.RIGHT), "at an insertion: RIGHT moves behind it")
        assertEquals(7, shift.offset(5, Bias.RIGHT), "at the start of a deletion: unaffected")
        assertEquals(7, shift.offset(6, Bias.LEFT), "inside a deletion: its start")
        assertEquals(7, shift.offset(7, Bias.LEFT), "after a deletion: shifted")
        assertEquals(9, shift.offset(9, Bias.RIGHT), "at the start of a replacement")
        assertEquals(12, shift.offset(10, Bias.LEFT), "after the replacement")
        assertEquals(14, shift.offset(12, Bias.RIGHT))
    }

    @Test
    fun `edit shift agrees with applying the edits for insertions at line starts`() {
        val random = Random(7)
        repeat(200) {
            val text = (0 until random.nextInt(1, 8)).joinToString("\n") { "x".repeat(random.nextInt(0, 5)) }
            val snapshot = TextSnapshot(text, 0)
            val edits = (0 until snapshot.lineCount).filter { random.nextBoolean() }
                .map { TextEdit.insert(snapshot.lineIndex.lineStart(it), "# ") }
            val after = StringBuilder(text).apply { edits.reversed().forEach { insert(it.range.start, it.newText) } }.toString()
            val shift = EditShift(edits)
            for (offset in 0..text.length) {
                for (bias in Bias.entries) {
                    val moved = shift.offset(offset, bias)
                    assertEquals(text.substring(offset), after.substring(moved).replace("# ", ""), "'$text' @ $offset $bias")
                }
            }
        }
    }

    @Test
    fun `edit shift keeps the direction of a selection`() {
        val shift = EditShift(listOf(TextEdit.insert(0, "    "), TextEdit.insert(4, "    ")))
        assertEquals(CaretMemento(0, 12), shift.caret(Caret(0, 4)))
        assertEquals(CaretMemento(12, 0), shift.caret(Caret(4, 0)))
        assertEquals(CaretMemento.at(6), shift.caret(Caret.at(2)))
        assertEquals(CaretMemento.at(4), shift.caret(Caret.at(0)), "a caret moves along with inserted text")
    }

    @Test
    fun `line blocks cover the selected lines without a line reached at column 0`() {
        val snapshot = TextSnapshot("ab\ncd\nef", 0)
        assertEquals(LineBlock(1, 1), LineBlock.of(snapshot, Caret.at(4)))
        assertEquals(LineBlock(0, 1), LineBlock.of(snapshot, Caret(1, 4)))
        assertEquals(LineBlock(0, 0), LineBlock.of(snapshot, Caret(0, 3)), "ends at column 0 of line 1")
        assertEquals(LineBlock(1, 1), LineBlock.of(snapshot, Caret(3, 3)), "an empty selection at column 0 is its line")
        assertEquals(TextRange(3, 8), LineBlock(1, 2).range(snapshot))
        assertEquals("cd\nef", LineBlock(1, 2).text(snapshot))
        assertEquals(TextRange(3, 6), LineBlock(1, 1).deletionRange(snapshot))
        assertEquals(TextRange(5, 8), LineBlock(2, 2).deletionRange(snapshot))
        assertEquals(TextRange(0, 8), LineBlock(0, 2).deletionRange(snapshot))
    }

    @Test
    fun `indentation helpers`() {
        assertEquals(" \t ", Indentation.leadingWhitespace(" \t x "))
        assertEquals("", Indentation.leadingWhitespace("x"))
        assertEquals(6, Indentation.firstNonBlank(TextSnapshot("ab\n   cd", 0), 1))
        assertEquals(5, Indentation.firstNonBlank(TextSnapshot("ab\n  ", 0), 1), "a blank line: its end")
        assertEquals("    ", Indentation.spacesToNextStop(0))
        assertEquals(" ", Indentation.spacesToNextStop(7))
        assertTrue(Indentation.isIndentChar('\t'))
        assertFalse(Indentation.isIndentChar('\n'))
    }

    @Test
    fun `string literals are counted per line with escapes`() {
        fun inside(marked: String): Boolean = StringLiterals.isInsideString(TextSnapshot(marked.replace("|", ""), 0), marked.indexOf('|'))
        assertFalse(inside("|\"a\""))
        assertTrue(inside("\"|a\""))
        assertFalse(inside("\"a\"|"))
        assertTrue(inside("\"a\\\"|b\""), "an escaped quote does not end the string")
        assertFalse(inside("\"a\"\n|"), "each line on its own")
        assertTrue(inside("x \"\" \"|"))
    }
}
