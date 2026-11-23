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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * What the text area shows while the completion list is open: the text that accepting the highlighted item would give,
 * drawn in place without changing the text. It is an immutable value of text [version], computed by [of] from the
 * [CompletionAcceptance] of the highlighted item, so the preview and the acceptance never disagree.
 *
 * The preview replaces the display of one [line] of the text by [rows]. The first row is the text of the line before
 * the replaced range followed by the first line of the inserted text. Every further line of the inserted text is a row
 * of its own, already indented by the acceptance. The text of the line behind the replaced range, the tail, follows the
 * inserted text on the last row. The rows after the first are [addedLines], which the text area shows below [line]
 * while the lines of the text below move down.
 *
 * A row holds real text and light text. Real text is text of the line drawn as it is: on the first row the columns
 * before [realColumns], and on the last row the tail from [tailColumn] on. The columns before [realColumns] are the text
 * before the replaced range and the beginning of the replaced text that the inserted text begins with, compared
 * character by character and with case. Light text is the rest of the inserted text, given by [ghost]. The rest of the
 * replaced text is not shown. So typed text that the proposal continues stays real, also when the replaced range goes on
 * behind the caret, as with `fo|bar` and the proposal `foo`. A proposal in another case or one that matches inside the
 * word shares no beginning with the replaced text, and then the whole inserted text is light in place of it.
 *
 * @property version the version of the text the preview belongs to.
 * @property line the line of the text the preview is drawn on.
 * @property lineStart the offset of the start of [line].
 * @property rows the texts of the rows that show [line], at least one.
 * @property realColumns the number of columns at the start of the first row that are real text of [line].
 * @property tailOffset the offset of [line] where the tail starts. That is the end of the replaced range.
 * @property tailColumn the column of the last row where the tail starts.
 */
public data class CompletionPreview(
    public val version: Long,
    public val line: Int,
    public val lineStart: Int,
    public val rows: List<String>,
    public val realColumns: Int,
    public val tailOffset: Int,
    public val tailColumn: Int,
) {
    init {
        require(rows.isNotEmpty()) { "a preview shows at least one row" }
        require(realColumns in 0..rows.first().length) { "realColumns $realColumns outside the first row" }
        require(tailColumn in 0..rows.last().length) { "tailColumn $tailColumn outside the last row" }
    }

    /** The number of rows the preview shows below [line]. The lines of the text below it move down by this number. */
    public val addedLines: Int get() = rows.size - 1

    /** The index of the last row, which holds the tail. */
    private val lastRow: Int get() = rows.size - 1

    /**
     * Returns the light columns of [row], from the start until the end of the returned range. On the first row they
     * start after the real columns, on the last row they end before the tail, and rows between are light as a whole.
     */
    public fun ghost(row: Int): TextRange {
        val start = if (row == 0) realColumns else 0
        val end = if (row == lastRow) tailColumn else rows[row].length
        return TextRange(start, maxOf(start, end))
    }

    /**
     * Returns the pieces of real text on [row]. Each piece says where it stands on the row and which columns of [line]
     * it shows, so the syntax colours of [line] can be moved onto the row.
     */
    public fun realRuns(row: Int): List<RealRun> = buildList {
        if (row == 0 && realColumns > 0) add(RealRun(0, 0, realColumns))
        val tail = rows[row].length - tailColumn
        if (row == lastRow && tail > 0) add(RealRun(tailColumn, tailOffset - lineStart, tail))
    }

    /**
     * Returns the offset of the text that the place at [column] of [row] shows. Real text answers its own offset. A
     * place in the light text answers [tailOffset], the end of the replaced range, which is where the text continues
     * once the preview ends.
     */
    public fun offsetAt(row: Int, column: Int): Int = when {
        row == 0 && column <= realColumns -> lineStart + maxOf(0, column)
        row == lastRow && column >= tailColumn -> tailOffset + minOf(column, rows[row].length) - tailColumn
        else -> tailOffset
    }

    /** Returns the column of the last row that shows [offset] of the tail. [offset] lies between [tailOffset] and the end of [line]. */
    public fun tailColumnOf(offset: Int): Int = tailColumn + offset - tailOffset

    /**
     * A piece of real text on a row.
     *
     * @property rowColumn the column of the row where the piece starts.
     * @property lineColumn the column of [line] that the piece starts with.
     * @property length the number of characters of the piece.
     */
    public data class RealRun(public val rowColumn: Int, public val lineColumn: Int, public val length: Int)

    /** Computes previews. */
    public companion object {
        /**
         * Returns the preview of [acceptance] in [snapshot], the text the acceptance was computed in. Returns null when
         * the replaced range spans more than one line, because the preview replaces the display of one line only.
         * Completion replaces a part of one word, so that does not happen while a session is open.
         */
        internal fun of(acceptance: CompletionAcceptance, snapshot: TextSnapshot): CompletionPreview? {
            val range = acceptance.edit.range
            val index = snapshot.lineIndex
            val line = index.lineOf(range.start)
            if (index.lineOf(range.end) != line) return null
            val lineStart = index.lineStart(line)
            val text = snapshot.lineText(line)
            val start = range.start - lineStart
            val end = range.end - lineStart
            val inserted = acceptance.edit.newText.split('\n')
            val before = text.substring(0, start)
            val tail = text.substring(end)
            val rows = inserted.mapIndexed { row, part ->
                val head = if (row == 0) before + part else part
                if (row == inserted.lastIndex) head + tail else head
            }
            val realColumns = start + acceptance.edit.newText.commonPrefixWith(text.substring(start, end)).length
            val tailColumn = rows.last().length - tail.length
            return CompletionPreview(snapshot.version, line, lineStart, rows, realColumns, range.end, tailColumn)
        }
    }
}
