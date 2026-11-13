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

import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange

/**
 * A text written for the text of a model that is woven from text of its own and from pieces of the code of a cell. It
 * knows where each piece of the code stands in it, so the positions of the code can be followed into the text of the
 * model. The rules of [TextBlocks] that put code into a block build their text this way, and so the mapping of a cell
 * follows its saving rule exactly.
 *
 * @property text the written text.
 * @property spans where the pieces of the code stand in [text], in the order of the code.
 * @property anchor where the code would stand in [text] if it had any character, or null if [text] has no place for
 *   code. It matters only while [spans] is empty.
 */
internal class WovenText private constructor(val text: String, val spans: List<CodeSpan>, val anchor: Int?) {

    /** This text followed by [other]. The places of the code of [other] move behind this text. */
    operator fun plus(other: WovenText): WovenText =
        WovenText(text + other.text, spans + other.spans.map { it.movedBy(text.length) }, anchor ?: other.anchor?.plus(text.length))

    /**
     * This text with [indent] put in front of every line that is not blank. A piece of code moves by the indentation
     * that is put in front of it. It is the one rule by which [TextBlocks] indents a statement.
     */
    fun indented(indent: String): WovenText {
        if (indent.isEmpty()) return this
        val lines = text.split('\n')
        val starts = ArrayList<Int>()
        var start = 0
        for (line in lines) {
            if (line.isNotBlank()) starts += start
            start += line.length + 1
        }
        fun moved(offset: Int): Int = offset + indent.length * starts.count { it <= offset }
        val written = lines.joinToString("\n") { if (it.isBlank()) it else indent + it }
        return WovenText(written, spans.map { CodeSpan(it.code, moved(it.at), it.length) }, anchor?.let(::moved))
    }

    companion object {

        /** [text] that holds no code. */
        fun literal(text: String): WovenText = WovenText(text, emptyList(), null)

        /** [text] that holds no code but is the place of an empty code, at the offset [anchor] of [text]. */
        fun placeFor(text: String, anchor: Int): WovenText = WovenText(text, emptyList(), anchor)

        /** The piece [piece] of the code, which starts at the offset [from] of the code. */
        fun code(piece: String, from: Int): WovenText = WovenText(piece, listOf(CodeSpan(from, 0, piece.length)), null)

        /** [parts] one after the other, with [separator] between each two of them. [parts] must not be empty. */
        fun joined(parts: List<WovenText>, separator: String): WovenText =
            parts.reduce { all, part -> all + literal(separator) + part }
    }
}

/**
 * A piece of the code of a cell in a [WovenText] or in the text of a model.
 *
 * @property code the offset in the code where the piece starts.
 * @property at the offset in the written text where the piece stands.
 * @property length the number of characters of the piece. A blank line of the code is a piece without characters.
 */
internal class CodeSpan(val code: Int, val at: Int, val length: Int) {

    /** The offset in the code behind the piece. */
    val codeEnd: Int get() = code + length

    /** The offset in the written text behind the piece. */
    val end: Int get() = at + length

    /** This piece in a written text that starts [delta] characters later. */
    fun movedBy(delta: Int): CodeSpan = CodeSpan(code, at + delta, length)
}

/**
 * One line of the code of a cell.
 *
 * @property text the line without its line separator.
 * @property start the offset in the code where the line starts.
 */
internal class CodeLine(val text: String, val start: Int) {

    /** True if the line holds nothing but whitespace. */
    val isBlank: Boolean get() = text.isBlank()

    /** The whole line. */
    fun whole(): WovenText = WovenText.code(text, start)

    /** The line without the whitespace at its ends. A blank line gives [emptied]. */
    fun trimmed(): WovenText {
        if (isBlank) return emptied()
        val from = text.indexOfFirst { !it.isWhitespace() }
        val to = text.indexOfLast { !it.isWhitespace() } + 1
        return WovenText.code(text.substring(from, to), start + from)
    }

    /** The line without the whitespace at its end. A blank line gives [emptied]. */
    fun trimmedEnd(): WovenText = if (isBlank) emptied() else WovenText.code(text.trimEnd(), start)

    /** Nothing of the line, at the place of its end, as a blank line is written. */
    fun emptied(): WovenText = WovenText.code("", start + text.length)

    companion object {
        /** The lines of [code], split at each line feed, with blank lines at the start and at the end left out. */
        fun trimmedLinesOf(code: String): List<CodeLine> {
            val lines = ArrayList<CodeLine>()
            var start = 0
            for (line in code.split('\n')) {
                lines += CodeLine(line, start)
                start += line.length + 1
            }
            return lines.dropWhile { it.isBlank }.dropLastWhile { it.isBlank }
        }
    }
}

/**
 * An edit of the text of a model whose new text is [written].
 *
 * @property range the range of the text that is replaced.
 * @property written the text that takes its place, with the places of the code in it.
 */
internal class WovenEdit(val range: TextRange, val written: WovenText) {

    /** The plain text edit. */
    fun edit(): TextEdit = TextEdit(range, written.text)
}
