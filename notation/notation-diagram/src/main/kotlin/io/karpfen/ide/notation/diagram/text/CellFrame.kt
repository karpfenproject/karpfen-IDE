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

import io.karpfen.ide.core.text.TextFrame
import io.karpfen.ide.core.text.TextRange

/**
 * The code of a code cell in its place in the text of its file: the whole text as saving that code would write it,
 * and the mapping of positions between the code and that text. It is made from the same written text that saving
 * writes, so the mapping follows the saving rule line by line. Each line of the code that the rule keeps stands in the
 * whole text as one piece. What the rule leaves out of a line, such as whitespace at its ends or a blank line around
 * the code, has no place of its own.
 *
 * An offset of the code in the part of a line that the rule leaves out lands at the nearest piece of the same line: at
 * the end of the line before it if no line break lies between, else at the start of the next piece. A blank line in
 * the middle of the code is a piece without characters at the place of its end. Mapping a range of the whole text
 * back starts it at the next piece and ends it at the end of the piece before.
 *
 * A code without any character, as a blank code that becomes an empty block, covers the empty range at the place
 * between the braces. Every range that holds that place, and does not only touch it, maps back to the empty range at the
 * end of the code.
 */
public class CellFrame private constructor(
    private val code: String,
    override val whole: String,
    private val spans: List<CodeSpan>,
    private val anchor: Int,
) : TextFrame {

    override val region: TextRange =
        if (spans.isEmpty()) TextRange.empty(anchor) else TextRange(spans.first().at, spans.last().end)

    override fun toWhole(offset: Int): Int {
        if (spans.isEmpty()) return anchor
        val at = offset.coerceIn(0, code.length)
        spans.firstOrNull { at in it.code..it.codeEnd }?.let { return it.at + (at - it.code) }
        val before = spans.lastOrNull { it.codeEnd < at }
        val after = spans.firstOrNull { it.code > at }
        return when {
            before == null -> checkNotNull(after).at
            after == null -> before.end
            '\n' in code.substring(before.codeEnd, at) -> after.at
            else -> before.end
        }
    }

    override fun toPiece(range: TextRange): TextRange? {
        if (range.end < region.start || range.start > region.end) return null
        if (!range.isEmpty && (range.end == region.start || range.start == region.end)) return null
        if (spans.isEmpty()) return TextRange.empty(code.length)
        val from = forward(maxOf(range.start, region.start))
        val to = backward(minOf(range.end, region.end))
        return when {
            from <= to -> TextRange(from, to)
            range.isEmpty -> TextRange.empty(from)
            else -> null
        }
    }

    /** The offset of the code at the offset [at] of the whole text, or at the start of the next piece behind it. */
    private fun forward(at: Int): Int {
        spans.firstOrNull { at in it.at..it.end }?.let { return it.code + (at - it.at) }
        return spans.first { it.at > at }.code
    }

    /** The offset of the code at the offset [at] of the whole text, or at the end of the last piece before it. */
    private fun backward(at: Int): Int {
        spans.lastOrNull { at in it.at..it.end }?.let { return it.code + (at - it.at) }
        return spans.last { it.end < at }.codeEnd
    }

    internal companion object {

        /**
         * The frame of [code] when saving it puts [written] in the place of [range] of [source], or null if [written]
         * has no place for code.
         */
        fun of(source: String, code: String, range: TextRange, written: WovenText): CellFrame? {
            if (written.spans.isEmpty() && written.anchor == null) return null
            val whole = source.replaceRange(range.start, range.end, written.text)
            return CellFrame(code, whole, written.spans.map { it.movedBy(range.start) }, range.start + (written.anchor ?: 0))
        }
    }
}
