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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret

/**
 * The whole lines a line action works on (lines [first]..[last], zero-based): the lines touched by the caret's
 * selection, without a last line the selection only reaches at its column 0 (selecting two full lines with the line
 * break of the second must not affect the third), or the caret's line without selection.
 */
internal data class LineBlock(val first: Int, val last: Int) {

    /** The block's lines from the start of [first] to the end of [last], without the final line break. */
    fun range(snapshot: TextSnapshot): TextRange =
        TextRange(snapshot.lineIndex.lineStart(first), snapshot.lineIndex.lineEnd(last))

    /** The block's text (lines joined by `\n`, without the final line break). */
    fun text(snapshot: TextSnapshot): String = snapshot.substring(range(snapshot))

    /** The lines of the block, in order. */
    val lines: IntRange get() = first..last

    /**
     * What deleting the block's lines removes: the lines with their following line break, or — for a block ending on
     * the last line — with the line break before them; the whole text for a block covering every line.
     */
    fun deletionRange(snapshot: TextSnapshot): TextRange {
        val index = snapshot.lineIndex
        return when {
            last + 1 < snapshot.lineCount -> TextRange(index.lineStart(first), index.lineStart(last + 1))
            first > 0 -> TextRange(index.lineEnd(first - 1), snapshot.length)
            else -> TextRange(0, snapshot.length)
        }
    }

    /**
     * The caret after deleting the block (see [deletionRange]) when it was at [column] of its line: the same column
     * (clamped) on the line that takes the block's place — the following line, or the previous one for a block at the
     * end of the text. Offsets of the text after the deletion.
     */
    fun caretAfterDeletion(snapshot: TextSnapshot, column: Int): Int {
        val index = snapshot.lineIndex
        return when {
            last + 1 < snapshot.lineCount -> {
                val next = last + 1
                index.lineStart(first) + column.coerceAtMost(index.lineEnd(next) - index.lineStart(next))
            }
            first > 0 -> index.lineStart(first - 1) + column.coerceAtMost(index.lineEnd(first - 1) - index.lineStart(first - 1))
            else -> 0
        }
    }

    /** Creation from a caret. */
    companion object {
        /** The block of [caret] in [snapshot]. */
        fun of(snapshot: TextSnapshot, caret: Caret): LineBlock {
            val selection = caret.selection
            val first = snapshot.lineIndex.lineOf(selection.start.coerceIn(0, snapshot.length))
            var last = snapshot.lineIndex.lineOf(selection.end.coerceIn(0, snapshot.length))
            if (last > first && selection.end == snapshot.lineIndex.lineStart(last)) last--
            return LineBlock(first, last)
        }
    }
}
