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
package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.TabStops
import io.karpfen.ide.ui.editor.model.completion.CompletionPreview
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Computes where the characters of one text snapshot are, in content coordinates. These are pixels before scrolling.
 * Row `n` occupies `n * lineHeight until (n + 1) * lineHeight`. The text of a row starts at [textLeft], and every
 * display column is one monospace cell of [charWidth]. Tabs advance to the next stop of [tabStops], the single tab rule
 * of the editor. The caret x, hit-testing and decoration rectangles all come from here, so they agree with each other
 * and with the drawn text. The class is pure and immutable and does not use Compose, so it is unit-tested directly.
 *
 * Without a preview, line `n` is drawn on row `n`. While a completion [preview] of the drawn snapshot shows, its line
 * is drawn as the rows of the preview, and the lines below it move down by the rows it adds, following [shift]. This is
 * display only. Offsets and lines stay those of the text, and every position is drawn where the preview shows it. On
 * the line of the preview, positions up to the end of the replaced range stay where they are in the text, and the
 * tail behind the replaced range is drawn behind the inserted text on the last row of the preview. A caret position
 * at the end of the replaced range belongs to the replaced text, and the character there belongs to the tail. Hit
 * testing reads the rows as they are drawn, so a click lands on the text the user sees.
 *
 * Characters wider than one cell, such as CJK characters and emoji, are drawn wider than their cell. Positions after
 * them on the same line are then slightly off. This is the usual limitation of an editor based on cells.
 *
 * @param preview the completion preview to show, or null. A preview of another version than [snapshot] is not shown.
 */
internal class TextGeometry(
    val snapshot: TextSnapshot,
    val tabStops: TabStops,
    val charWidth: Float,
    val lineHeight: Float,
    val textLeft: Float,
    preview: CompletionPreview? = null,
) {

    /** The shown completion preview. It belongs to [snapshot], or else it is null. */
    val preview: CompletionPreview? = preview?.takeIf { it.version == snapshot.version && it.line < snapshot.lineCount }

    /** The display-only shift of the lines below the line of [preview]. It is [LineShift.NONE] without a preview. */
    val shift: LineShift = this.preview?.let { LineShift(it.line, it.addedLines) } ?: LineShift.NONE

    /** The number of drawn rows. That is the line count plus the rows the preview adds. */
    val rowCount: Int get() = shift.rowCount(snapshot.lineCount)

    /**
     * A horizontal piece of a range on one [line], from [left] to [right], drawn on [row]. The row is the line itself
     * unless a preview moves the line or draws the piece on one of its rows.
     */
    data class Segment(val line: Int, val left: Float, val right: Float, val row: Int = line)

    /** Returns the top of [row]. */
    fun rowTop(row: Int): Float = row * lineHeight

    /** Returns the top of the row where [line] starts. */
    fun lineTop(line: Int): Float = rowTop(shift.rowOf(line))

    /** Returns the line that contains [offset], clamped to the text. */
    fun lineOf(offset: Int): Int = snapshot.lineIndex.lineOf(offset.coerceIn(0, snapshot.length))

    /** Returns the row of the caret position [offset], clamped to the text. */
    fun rowOf(offset: Int): Int {
        val clamped = offset.coerceIn(0, snapshot.length)
        return if (inTail(clamped, cell = false)) tailRow() else shift.rowOf(lineOf(clamped))
    }

    /** Returns the x of the caret position [offset], clamped to the text. */
    fun xOf(offset: Int): Float {
        val clamped = offset.coerceIn(0, snapshot.length)
        if (inTail(clamped, cell = false)) return tailX(clamped)
        val position = snapshot.positionOf(clamped)
        return textLeft + tabStops.displayColumn(snapshot.lineText(position.line), position.column) * charWidth
    }

    /** Returns the row of the character at [offset], clamped to the text. */
    fun cellRowOf(offset: Int): Int = if (inTail(offset.coerceIn(0, snapshot.length), cell = true)) tailRow() else rowOf(offset)

    /** Returns the left edge of the character at [offset], clamped to the text. */
    fun cellXOf(offset: Int): Float {
        val clamped = offset.coerceIn(0, snapshot.length)
        return if (inTail(clamped, cell = true)) tailX(clamped) else xOf(clamped)
    }

    /** Returns the right end of the text of [line]. */
    fun lineRight(line: Int): Float = xOf(snapshot.lineIndex.lineEnd(line))

    /**
     * Returns the number of display columns of the widest row, for the content width. It makes one pass over the text
     * and uses [TabStops] only for lines that contain a tab. The rows of the preview count too.
     */
    fun maxDisplayColumns(): Int {
        val text = snapshot.text
        val index = snapshot.lineIndex
        var widest = preview?.rows?.maxOf { tabStops.displayColumn(it, it.length) } ?: 0
        for (line in 0 until snapshot.lineCount) {
            val start = index.lineStart(line)
            val end = index.lineEnd(line)
            var hasTab = false
            for (offset in start until end) {
                if (text[offset] == '\t') {
                    hasTab = true
                    break
                }
            }
            val columns = if (hasTab) tabStops.displayColumn(text.subSequence(start, end), end - start) else end - start
            if (columns > widest) widest = columns
        }
        return widest
    }

    /**
     * Returns the caret position nearest to [x] and [y] for a click. It takes the row under [y], clamped to the rows,
     * and the nearest boundary between display cells of the text drawn on that row. Inside a tab it takes the nearer
     * bound of the tab. On the rows of the preview, a place in the light text answers the end of the replaced range.
     */
    fun offsetAt(x: Float, y: Float): Int {
        val row = rowAt(y).coerceIn(0, rowCount - 1)
        val displayColumn = ((x - textLeft) / charWidth).roundToInt()
        return offsetOnRow(row, tabStops.columnAt(rowText(row), displayColumn))
    }

    /**
     * Returns the offset of the character under [x] and [y] for hovering. Returns null outside the text, which means
     * above the first row, below the last one, left of the text, right of the end of the row or on the light text of
     * the preview.
     */
    fun characterAt(x: Float, y: Float): Int? {
        val row = rowAt(y)
        if (y < 0f || row >= rowCount || x < textLeft) return null
        val text = rowText(row)
        val displayColumn = floor((x - textLeft) / charWidth).toInt()
        if (displayColumn >= tabStops.displayColumn(text, text.length)) return null
        val column = columnUnder(text, displayColumn)
        val index = previewRowOf(row)
        if (index >= 0 && column in checkNotNull(preview).ghost(index)) return null
        return offsetOnRow(row, column)
    }

    /**
     * Returns the column of the character of [line] whose cell contains [displayColumn]. [TabStops.columnAt] answers
     * the nearer bound of a cell, so a bound right of the point steps back to the character before it.
     */
    private fun columnUnder(line: CharSequence, displayColumn: Int): Int {
        val column = tabStops.columnAt(line, displayColumn)
        return if (column > 0 && tabStops.displayColumn(line, column) > displayColumn) column - 1 else column
    }

    /**
     * Returns the pieces of [range] on the lines of [lines]. On every line except the last, the piece extends one cell
     * past the end of the line, which shows the selected line break. An empty range yields nothing.
     */
    fun segments(range: TextRange, lines: IntRange): List<Segment> = segments(listOf(range), lines).single()

    /**
     * Returns the [segments] of each of [ranges], in the same order. The x positions of all bounds on one line come from
     * one pass over that line with [TabStops.displayColumns]. So k ranges on a line of n characters, such as find
     * matches, cost O(n + k log k). One call of [xOf] per bound would cost O(k * n). On the line of the preview, a piece
     * is split into the part before the tail, which stays in place, and the part in the tail, which is drawn on the last
     * row of the preview.
     */
    fun segments(ranges: List<TextRange>, lines: IntRange): List<List<Segment>> {
        val result = List(ranges.size) { ArrayList<Segment>() }
        val byLine = ranges.flatMapIndexed { index, range -> pieces(range, lines).map { index to it } }.groupBy { it.second.line }
        for (line in byLine.keys.sorted()) {
            val onLine = byLine.getValue(line)
            if (line == preview?.line) {
                for ((index, piece) in onLine) result[index] += previewSegments(piece)
                continue
            }
            val row = shift.rowOf(line)
            val bounds = onLine.flatMap { (_, piece) -> listOf(piece.start, piece.end) }.distinct().sorted().toIntArray()
            val columns = tabStops.displayColumns(snapshot.lineText(line), bounds)
            fun x(column: Int): Float = textLeft + columns[bounds.binarySearch(column)] * charWidth
            for ((index, piece) in onLine) {
                result[index] += Segment(line, x(piece.start), x(piece.end) + if (piece.pastLineEnd) charWidth else 0f, row)
            }
        }
        return result
    }

    /** Returns the range of [line] without its line break. */
    fun lineRange(line: Int): TextRange = snapshot.lineRange(line)

    /**
     * The columns from [start] until [end] of [line] that a range covers. [pastLineEnd] is true when the range goes on
     * to the next line. The piece then includes the cell of the line break.
     */
    private class LinePiece(val line: Int, val start: Int, val end: Int, val pastLineEnd: Boolean)

    /** Returns the pieces of [range] on the lines of [lines], in line order. */
    private fun pieces(range: TextRange, lines: IntRange): List<LinePiece> {
        if (range.isEmpty || lines.isEmpty()) return emptyList()
        val first = lineOf(range.start)
        val last = lineOf(range.end)
        return (maxOf(first, lines.first)..minOf(last, lines.last)).map { line ->
            val lineRange = lineRange(line)
            val start = if (line == first) range.start - lineRange.start else 0
            val end = if (line == last) range.end - lineRange.start else lineRange.length
            LinePiece(line, start, end, pastLineEnd = line != last)
        }
    }

    /**
     * Returns the segments of [piece] on the line of the preview. The part before the tail keeps the place it has in
     * the text, on the first row. The part in the tail, with the cell of the line break, is drawn behind the inserted
     * text on the last row.
     */
    private fun previewSegments(piece: LinePiece): List<Segment> {
        val shown = checkNotNull(preview)
        val lineStart = shown.lineStart
        val tail = shown.tailOffset - lineStart
        val segments = ArrayList<Segment>(2)
        if (piece.start < tail) {
            val end = minOf(piece.end, tail)
            segments += Segment(piece.line, xOf(lineStart + piece.start), xOf(lineStart + end), piece.line)
        }
        if (piece.end > tail || piece.start >= tail || piece.pastLineEnd) {
            val left = tailX(lineStart + maxOf(piece.start, tail))
            val right = tailX(lineStart + piece.end) + if (piece.pastLineEnd) charWidth else 0f
            segments += Segment(piece.line, left, right, tailRow())
        }
        return segments
    }

    /**
     * True if [offset] is drawn in the tail of the preview line. For a caret position ([cell] false) that is every
     * position after the end of the replaced range. For a character ([cell] true) it includes the character at the end
     * of the replaced range, the first one of the tail.
     */
    private fun inTail(offset: Int, cell: Boolean): Boolean {
        val shown = preview ?: return false
        val lineEnd = snapshot.lineIndex.lineEnd(shown.line)
        if (offset > lineEnd) return false
        return offset > shown.tailOffset || (cell && offset == shown.tailOffset)
    }

    /** Returns the row of the tail, the last row of the preview. */
    private fun tailRow(): Int = shift.lastRowOf(shift.line)

    /** Returns the x of [offset] of the tail on the last row of the preview. */
    private fun tailX(offset: Int): Float {
        val shown = checkNotNull(preview)
        return textLeft + tabStops.displayColumn(shown.rows.last(), shown.tailColumnOf(offset)) * charWidth
    }

    /** Returns the text drawn on [row]: a row of the preview, or the line drawn there. */
    private fun rowText(row: Int): String {
        val index = previewRowOf(row)
        return if (index >= 0) checkNotNull(preview).rows[index] else snapshot.lineText(shift.lineAt(row))
    }

    /** Returns the offset that [column] of the text drawn on [row] shows. */
    private fun offsetOnRow(row: Int, column: Int): Int {
        val index = previewRowOf(row)
        if (index >= 0) return checkNotNull(preview).offsetAt(index, column)
        return snapshot.lineIndex.lineStart(shift.lineAt(row)) + column
    }

    /** Returns the index of [row] among the rows of the preview, or -1 if it is not one of them. */
    private fun previewRowOf(row: Int): Int {
        val shown = preview ?: return -1
        return if (row in shown.line..shown.line + shown.addedLines) row - shown.line else -1
    }

    /** Returns the row under the content y [y], without clamping. Without a line height it returns 0. */
    private fun rowAt(y: Float): Int = if (lineHeight <= 0f) 0 else floor(y / lineHeight).toInt()
}
