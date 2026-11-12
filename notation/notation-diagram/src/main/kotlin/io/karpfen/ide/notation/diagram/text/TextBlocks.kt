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
package io.karpfen.ide.notation.diagram.text

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange

/**
 * The places of blocks in the text of a model and the small text edits that put a statement into a block or take a
 * construct out. A block is a pair of braces with what lies between them. The graphical edits of the diagrams are made
 * of these edits, so a change in a diagram touches only the lines it is about.
 *
 * Braces inside string literals and inside line comments do not count. The text is expected to be free of syntax
 * errors, as the text of a diagram is.
 */
internal object TextBlocks {

    /** One step of indentation. */
    private const val INDENT = "    "

    /**
     * The index of the brace that closes the one at [open] of [text], or the index of the last character if none
     * does.
     */
    fun closing(text: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < text.length) {
            val next = skipped(text, index)
            if (next != index) {
                index = next
                continue
            }
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
            index++
        }
        return text.length - 1
    }

    /** The first block that opens at or after [from] of [text]: its braces and what lies between. Null if none opens. */
    fun blockFrom(text: String, from: Int): TextRange? {
        var index = from
        while (index < text.length) {
            val next = skipped(text, index)
            if (next != index) {
                index = next
                continue
            }
            if (text[index] == '{') return TextRange(index, closing(text, index) + 1)
            index++
        }
        return null
    }

    /**
     * The block that follows the word [keyword] directly inside the block [within] of [text], or null if [within] has
     * no such word at its own level.
     */
    fun blockNamed(text: String, keyword: String, within: TextRange): TextRange? {
        var depth = 0
        var index = within.start
        while (index < within.end) {
            val next = skipped(text, index)
            if (next != index) {
                index = next
                continue
            }
            when {
                text[index] == '{' -> depth++
                text[index] == '}' -> depth--
                depth == 1 && text.startsWith(keyword, index) && isWord(text, index, keyword.length) -> return blockFrom(text, index + keyword.length)
            }
            index++
        }
        return null
    }

    /**
     * The edit that puts [statement] at the end of the block [block] of [text], on lines of its own, one step deeper
     * than the line the block closes on. A statement of several lines keeps the indentation its lines have among each
     * other.
     */
    fun append(text: String, block: TextRange, statement: String): TextEdit {
        val close = block.end - 1
        val lineStart = lineStartOf(text, close)
        val before = text.substring(lineStart, close)
        val outer = indentOf(text, close)
        return if (before.isBlank() && lineStart > block.start) {
            TextEdit(TextRange(lineStart, lineStart), indented(statement, outer + INDENT) + "\n")
        } else {
            val gap = close - text.substring(block.start + 1, close).takeLastWhile { it == ' ' || it == '\t' }.length
            TextEdit(TextRange(gap, close), "\n" + indented(statement, outer + INDENT) + "\n" + outer)
        }
    }

    /**
     * The edit that puts [statement] into the block [block] of [text] right after [after], which lies in the block,
     * or at the start of the block if [after] is null. The statement stands on lines of its own, one step deeper than
     * the line the block opens on.
     */
    fun insert(text: String, block: TextRange, statement: String, after: TextRange?): TextEdit {
        if (text.substring(block.start + 1, block.end - 1).isBlank()) return append(text, block, statement)
        val inner = indentOf(text, block.start) + INDENT
        val at = after?.end ?: (block.start + 1)
        return TextEdit(TextRange(at, at), "\n" + indented(statement, inner))
    }

    /**
     * The edit that takes the construct at [range] out of [text]. If nothing else stands on its lines, the lines go
     * with it.
     */
    fun removal(text: String, range: TextRange): TextEdit {
        val lineStart = lineStartOf(text, range.start)
        val lineEnd = text.indexOf('\n', range.end).let { if (it < 0) text.length else it }
        val alone = text.substring(lineStart, range.start).isBlank() && text.substring(range.end, lineEnd).isBlank()
        return if (alone) TextEdit(TextRange(lineStart, minOf(lineEnd + 1, text.length)), "") else TextEdit(range, "")
    }

    /**
     * The construct at [range] of [text] with the indentation of its first line taken off every line, ready to be put
     * somewhere else.
     */
    fun lifted(text: String, range: TextRange): String {
        val indent = indentOf(text, range.start)
        return text.substring(range.start, range.end).lines().joinToString("\n") { it.removePrefix(indent) }
    }

    /**
     * What stands between the braces of the block [block] of [text], as a user edits it: without the blank lines
     * around it and without the indentation its lines share.
     */
    fun inner(text: String, block: TextRange): String {
        val lines = text.substring(block.start + 1, block.end - 1).lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val shared = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return lines.joinToString("\n") { it.drop(minOf(shared, it.length)).trimEnd() }
    }

    /**
     * A block with [content] between its braces, to stand on a line that is indented by [indent]. Nothing gives an
     * empty block, one line gives a block on one line, and several lines give a block with each line one step deeper
     * than [indent].
     */
    fun braced(content: String, indent: String): String {
        val lines = content.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return when (lines.size) {
            0 -> "{ }"
            1 -> "{ ${lines.single().trim()} }"
            else -> "{\n" + lines.joinToString("\n") { if (it.isBlank()) "" else indent + INDENT + it } + "\n" + indent + "}"
        }
    }

    /** One step of indentation behind [indent]. */
    fun deeper(indent: String): String = indent + INDENT

    /** The whitespace at the start of the line that holds [offset] of [text]. */
    fun indentOf(text: String, offset: Int): String =
        text.substring(lineStartOf(text, offset)).takeWhile { it == ' ' || it == '\t' }

    private fun lineStartOf(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    private fun indented(statement: String, indent: String): String =
        statement.lines().joinToString("\n") { if (it.isBlank()) it else indent + it }

    /** True if the [length] characters at [index] of [text] are a whole word and not a part of a longer one. */
    private fun isWord(text: String, index: Int, length: Int): Boolean {
        val before = index == 0 || !text[index - 1].isLetterOrDigit()
        val after = index + length >= text.length || !text[index + length].isLetterOrDigit()
        return before && after
    }

    /**
     * The index after the string literal or line comment that starts at [index] of [text], or [index] itself if none
     * starts there.
     */
    private fun skipped(text: String, index: Int): Int = when {
        text[index] == '"' || text[index] == '\'' -> {
            var end = index + 1
            while (end < text.length && text[end] != text[index] && text[end] != '\n') end += if (text[end] == '\\') 2 else 1
            minOf(end + 1, text.length)
        }
        text.startsWith(KarpfenSyntax.LINE_COMMENT, index) -> text.indexOf('\n', index).let { if (it < 0) text.length else it }
        else -> index
    }
}
