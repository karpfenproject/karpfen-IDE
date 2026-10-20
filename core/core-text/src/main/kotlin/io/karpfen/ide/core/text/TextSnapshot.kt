package io.karpfen.ide.core.text

/**
 * An immutable version of a document's text. Snapshots are freely shareable between threads (spec §1, Threading);
 * background work (tokenizing, parsing, analysis) only ever reads snapshots.
 *
 * Equality is identity: two snapshots are the same snapshot, not merely equal text (comparing large texts would be
 * O(n)). Compare [version]s when the question is "is this result still current?".
 *
 * @property text the complete text, `\n` line separators only.
 * @property version monotonically increasing per [EditableText], starting at 0; every applied change produces
 *   `version + 1`, including undo and redo.
 */
public class TextSnapshot(public val text: String, public val version: Long) {

    init {
        require(version >= 0) { "version must be >= 0, was $version" }
    }

    /** Line/offset mapping for [text], computed on first use. */
    public val lineIndex: LineIndex by lazy { LineIndex(text) }

    /** Length of [text] in UTF-16 code units. */
    public val length: Int get() = text.length

    /** Number of lines (see [LineIndex.lineCount]). */
    public val lineCount: Int get() = lineIndex.lineCount

    /** Character at [offset]. */
    public operator fun get(offset: Int): Char = text[offset]

    /** The text covered by [range]. */
    public fun substring(range: TextRange): String = text.substring(range.start, range.end)

    /** Range of [line] without its terminating `\n`. */
    public fun lineRange(line: Int): TextRange = TextRange(lineIndex.lineStart(line), lineIndex.lineEnd(line))

    /** Text of [line] without its terminating `\n`. */
    public fun lineText(line: Int): String = substring(lineRange(line))

    /** Position of [offset] (see [LineIndex.positionOf]). */
    public fun positionOf(offset: Int): TextPosition = lineIndex.positionOf(offset)

    /** Clamped offset of [position] (see [LineIndex.offsetOf]). */
    public fun offsetOf(position: TextPosition): Int = lineIndex.offsetOf(position)

    /** Clamped offset of [line]/[column] (see [LineIndex.offsetOf]). */
    public fun offsetOf(line: Int, column: Int): Int = lineIndex.offsetOf(line, column)

    override fun toString(): String = "TextSnapshot(version=$version, length=$length)"
}
