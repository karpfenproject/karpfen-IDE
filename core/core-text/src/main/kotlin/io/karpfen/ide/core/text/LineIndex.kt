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

    /** Number of lines, always `>= 1`. */
    public val lineCount: Int
        get() = TODO("WP-TEXT: line count of ${text.length} chars")

    /**
     * Offset of the first character of [line].
     * @throws IllegalArgumentException if [line] is not in `0 until lineCount`.
     */
    public fun lineStart(line: Int): Int = TODO("WP-TEXT: line start")

    /**
     * Offset directly after the last character of [line], excluding its `\n`.
     * @throws IllegalArgumentException if [line] is not in `0 until lineCount`.
     */
    public fun lineEnd(line: Int): Int = TODO("WP-TEXT: line end")

    /**
     * Line containing [offset]; an offset on a `\n` belongs to the line it terminates, `offset == text.length` to the
     * last line.
     * @throws IllegalArgumentException if [offset] is not in `0..text.length`.
     */
    public fun lineOf(offset: Int): Int = TODO("WP-TEXT: binary search over line starts")

    /**
     * Position of [offset].
     * @throws IllegalArgumentException if [offset] is not in `0..text.length`.
     */
    public fun positionOf(offset: Int): TextPosition = TODO("WP-TEXT: position of offset")

    /**
     * Offset of [position], clamped: a line beyond the last line maps to the end of the text, a column beyond the end
     * of its line maps to [lineEnd] of that line. Never throws.
     */
    public fun offsetOf(position: TextPosition): Int = TODO("WP-TEXT: clamped offset of position")

    /** Same as [offsetOf] with a [TextPosition]; negative arguments are clamped to 0. Never throws. */
    public fun offsetOf(line: Int, column: Int): Int = TODO("WP-TEXT: clamped offset of line/column")
}
