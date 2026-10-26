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
package io.karpfen.ide.core.text

/**
 * An immutable version of a document's text. Snapshots are freely shareable between threads: an [EditableText] has a
 * single writer, and background work (tokenizing, parsing, analysis) only ever reads snapshots.
 *
 * Equality is identity: two snapshots are the same snapshot, not merely equal text (comparing large texts would be
 * O(n)). Compare [version]s (of the same [lineage]) when the question is "is this result still current?".
 *
 * @property text the complete text, `\n` line separators only.
 * @property version monotonically increasing per [EditableText] (per [lineage]), starting at 0; every applied change
 *   produces `version + 1`, including undo and redo.
 * @property lineage the text this snapshot is a version of: shared by all snapshots of one [EditableText]; a snapshot
 *   created on its own (default) is its own lineage. See [TextLineage].
 */
public class TextSnapshot(
    public val text: String,
    public val version: Long,
    public val lineage: TextLineage = TextLineage(),
) {

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
