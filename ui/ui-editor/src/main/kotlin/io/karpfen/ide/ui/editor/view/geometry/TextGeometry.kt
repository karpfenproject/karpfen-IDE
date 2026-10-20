package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.TabStops
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where the characters of one text snapshot are, in content coordinates (pixels, before scrolling): line `n` occupies
 * `n * lineHeight until (n + 1) * lineHeight`; the text of a line starts at [textLeft] and every display column is one
 * monospace cell of [charWidth] (tabs advance to the next stop of [tabStops], decision 40 of the work-package plan).
 * Caret x, hit-testing and decoration rectangles all come from here, so they agree with each other and with the drawn
 * text. Pure and immutable (no Compose), so it is unit-tested directly.
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

    /** Number of display columns of the widest line (for the content width). */
    fun maxDisplayColumns(): Int = (0 until snapshot.lineCount).maxOf { line ->
        val text = snapshot.lineText(line)
        if ('\t' in text) tabStops.displayColumn(text, text.length) else text.length
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
        var column = tabStops.columnAt(text, displayColumn)
        // columnAt answers the nearer bound; the character under the point is the one whose cell contains it.
        if (column > 0 && tabStops.displayColumn(text, column) > displayColumn) column--
        return snapshot.lineIndex.lineStart(line) + column
    }

    /**
     * The pieces of [range] on the lines of [lines]: on every line but the last the piece extends past the line's end by
     * one cell (the selected line break); an empty range yields nothing.
     */
    fun segments(range: TextRange, lines: IntRange): List<Segment> {
        if (range.isEmpty || lines.isEmpty()) return emptyList()
        val first = lineOf(range.start)
        val last = lineOf(range.end)
        val from = maxOf(first, lines.first)
        val to = minOf(last, lines.last)
        if (from > to) return emptyList()
        return (from..to).map { line ->
            val left = if (line == first) xOf(range.start) else textLeft
            val right = if (line == last) xOf(range.end) else lineRight(line) + charWidth
            Segment(line, left, right)
        }
    }

    /** The range of [line] (without its line break). */
    fun lineRange(line: Int): TextRange = snapshot.lineRange(line)

    private fun lineAt(y: Float): Int = if (lineHeight <= 0f) 0 else floor(y / lineHeight).toInt()
}
