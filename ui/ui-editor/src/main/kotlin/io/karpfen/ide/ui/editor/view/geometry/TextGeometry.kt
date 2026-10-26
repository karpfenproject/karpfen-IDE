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
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where the characters of one text snapshot are, in content coordinates (pixels, before scrolling): line `n` occupies
 * `n * lineHeight until (n + 1) * lineHeight`; the text of a line starts at [textLeft] and every display column is one
 * monospace cell of [charWidth] (tabs advance to the next stop of [tabStops], the one tab rule of the editor). Caret x,
 * hit-testing and decoration rectangles all come from here, so they agree with each other and with the drawn text. Pure
 * and immutable (no Compose), so it is unit-tested directly.
 *
 * Characters wider than one cell (CJK, emoji) are drawn wider than their cell; positions after them on the same line
 * are then slightly off, the usual limitation of a cell-based editor.
 */
internal class TextGeometry(
    val snapshot: TextSnapshot,
    val tabStops: TabStops,
    val charWidth: Float,
    val lineHeight: Float,
    val textLeft: Float,
) {

    /** A horizontal piece of a range on one [line], from [left] to [right]. */
    data class Segment(val line: Int, val left: Float, val right: Float)

    /** The top of [line]. */
    fun lineTop(line: Int): Float = line * lineHeight

    /** The line containing [offset] (clamped to the text). */
    fun lineOf(offset: Int): Int = snapshot.lineIndex.lineOf(offset.coerceIn(0, snapshot.length))

    /** The x of the caret position [offset] (clamped to the text). */
    fun xOf(offset: Int): Float {
        val clamped = offset.coerceIn(0, snapshot.length)
        val position = snapshot.positionOf(clamped)
        return textLeft + tabStops.displayColumn(snapshot.lineText(position.line), position.column) * charWidth
    }

    /** The right end of [line]'s text. */
    fun lineRight(line: Int): Float = xOf(snapshot.lineIndex.lineEnd(line))

    /**
     * Number of display columns of the widest line (for the content width): one pass over the text, [TabStops] only for
     * lines that contain a tab.
     */
    fun maxDisplayColumns(): Int {
        val text = snapshot.text
        val index = snapshot.lineIndex
        var widest = 0
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
     * The caret position nearest to ([x], [y]) for a click: the line under [y] (clamped to the text's lines), the
     * nearest boundary between display cells (inside a tab, its nearer bound).
     */
    fun offsetAt(x: Float, y: Float): Int {
        val line = lineAt(y).coerceIn(0, snapshot.lineCount - 1)
        val displayColumn = ((x - textLeft) / charWidth).roundToInt()
        return snapshot.lineIndex.lineStart(line) + tabStops.columnAt(snapshot.lineText(line), displayColumn)
    }

    /**
     * The offset of the character under ([x], [y]) for hovering, or null outside the text: above the first line, below
     * the last one, left of the text or right of the line's end.
     */
    fun characterAt(x: Float, y: Float): Int? {
        val line = lineAt(y)
        if (y < 0f || line >= snapshot.lineCount || x < textLeft) return null
        val text = snapshot.lineText(line)
        val displayColumn = floor((x - textLeft) / charWidth).toInt()
        if (displayColumn >= tabStops.displayColumn(text, text.length)) return null
        return snapshot.lineIndex.lineStart(line) + columnUnder(text, displayColumn)
    }

    /**
     * The column of the character of [line] whose cell contains [displayColumn]: [TabStops.columnAt] answers the nearer
     * bound of a cell, so a bound right of the point steps back to the character before it.
     */
    private fun columnUnder(line: CharSequence, displayColumn: Int): Int {
        val column = tabStops.columnAt(line, displayColumn)
        return if (column > 0 && tabStops.displayColumn(line, column) > displayColumn) column - 1 else column
    }

    /**
     * The pieces of [range] on the lines of [lines]: on every line but the last the piece extends past the line's end by
     * one cell (the selected line break); an empty range yields nothing.
     */
    fun segments(range: TextRange, lines: IntRange): List<Segment> = segments(listOf(range), lines).single()

    /**
     * The [segments] of each of [ranges], in the same order. The x positions of all bounds on one line come from one pass
     * over that line ([TabStops.displayColumns]), so k ranges on a line of n characters (find matches) cost
     * O(n + k log k), not O(k × n) as one [xOf] per bound would.
     */
    fun segments(ranges: List<TextRange>, lines: IntRange): List<List<Segment>> {
        val result = List(ranges.size) { ArrayList<Segment>() }
        val byLine = ranges.flatMapIndexed { index, range -> pieces(range, lines).map { index to it } }.groupBy { it.second.line }
        for (line in byLine.keys.sorted()) {
            val onLine = byLine.getValue(line)
            val bounds = onLine.flatMap { (_, piece) -> listOf(piece.start, piece.end) }.distinct().sorted().toIntArray()
            val columns = tabStops.displayColumns(snapshot.lineText(line), bounds)
            fun x(column: Int): Float = textLeft + columns[bounds.binarySearch(column)] * charWidth
            for ((index, piece) in onLine) {
                result[index] += Segment(line, x(piece.start), x(piece.end) + if (piece.pastLineEnd) charWidth else 0f)
            }
        }
        return result
    }

    /** The range of [line] (without its line break). */
    fun lineRange(line: Int): TextRange = snapshot.lineRange(line)

    /**
     * The columns [start] until [end] of [line] that a range covers; [pastLineEnd] when it goes on to the next line (its
     * piece then includes the line break's cell).
     */
    private class LinePiece(val line: Int, val start: Int, val end: Int, val pastLineEnd: Boolean)

    /** The pieces of [range] on the lines of [lines], in line order. */
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

    /** The line under content y [y], unclamped (0 without a line height). */
    private fun lineAt(y: Float): Int = if (lineHeight <= 0f) 0 else floor(y / lineHeight).toInt()
}
