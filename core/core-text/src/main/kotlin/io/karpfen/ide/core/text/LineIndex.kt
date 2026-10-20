package io.karpfen.ide.core.text

/**
 * Maps between offsets and line/column positions of one immutable text.
 *
 * Only `\n` separates lines (documents are normalized on load, spec §1). Text with `n` newlines has `n + 1` lines;
 * the empty text has one empty line, and a trailing newline yields a final empty line. Instances are immutable and
 * thread-safe; [TextSnapshot.lineIndex] creates one lazily per snapshot.
 *
 * Performance contract: construction is O(length) and every query is O(log lineCount) or better.
 */
public class LineIndex(private val text: String) {

    /** Start offset of every line, ascending; `lineStarts[0] == 0`. Never mutated after construction. */
    private val lineStarts: IntArray = computeLineStarts(text)

    /** Number of lines, always `>= 1`. */
    public val lineCount: Int
        get() = lineStarts.size

    /**
     * Offset of the first character of [line].
     * @throws IllegalArgumentException if [line] is not in `0 until lineCount`.
     */
    public fun lineStart(line: Int): Int {
        requireLine(line)
        return lineStarts[line]
    }

    /**
     * Offset directly after the last character of [line], excluding its `\n`.
     * @throws IllegalArgumentException if [line] is not in `0 until lineCount`.
     */
    public fun lineEnd(line: Int): Int {
        requireLine(line)
        return endOf(line)
    }

    /**
     * Line containing [offset]; an offset on a `\n` belongs to the line it terminates, `offset == text.length` to the
     * last line.
     * @throws IllegalArgumentException if [offset] is not in `0..text.length`.
     */
    public fun lineOf(offset: Int): Int {
        requireOffset(offset)
        return lineContaining(offset)
    }

    /**
     * Position of [offset].
     * @throws IllegalArgumentException if [offset] is not in `0..text.length`.
     */
    public fun positionOf(offset: Int): TextPosition {
        requireOffset(offset)
        val line = lineContaining(offset)
        return TextPosition(line, offset - lineStarts[line])
    }

    /**
     * Offset of [position], clamped: a line beyond the last line maps to the end of the text, a column beyond the end
     * of its line maps to [lineEnd] of that line. Never throws.
     */
    public fun offsetOf(position: TextPosition): Int = offsetOf(position.line, position.column)

    /** Same as [offsetOf] with a [TextPosition]; negative arguments are clamped to 0. Never throws. */
    public fun offsetOf(line: Int, column: Int): Int {
        if (line >= lineCount) return text.length
        val clampedLine = line.coerceAtLeast(0)
        val start = lineStarts[clampedLine]
        val end = endOf(clampedLine)
        // Compared as a length, so a huge column cannot overflow `start + column`.
        val clampedColumn = column.coerceAtLeast(0)
        return if (clampedColumn >= end - start) end else start + clampedColumn
    }

    override fun toString(): String = "LineIndex(lineCount=$lineCount, length=${text.length})"

    private fun endOf(line: Int): Int = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length

    /** The last line whose start is `<= offset` (binary search; [offset] is in bounds). */
    private fun lineContaining(offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (lineStarts[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }

    private fun requireLine(line: Int) {
        require(line in 0 until lineCount) { "line must be in 0 until $lineCount, was $line" }
    }

    private fun requireOffset(offset: Int) {
        require(offset in 0..text.length) { "offset must be in 0..${text.length}, was $offset" }
    }

    private companion object {
        fun computeLineStarts(text: String): IntArray {
            var newlines = 0
            for (char in text) if (char == '\n') newlines++
            val starts = IntArray(newlines + 1)
            var line = 1
            for (offset in text.indices) {
                if (text[offset] == '\n') starts[line++] = offset + 1
            }
            return starts
        }
    }
}
