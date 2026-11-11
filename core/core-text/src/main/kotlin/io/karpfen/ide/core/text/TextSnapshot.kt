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
 * An immutable version of a document's text. Snapshots can be shared freely between threads. An [EditableText] has a
 * single writer, and background work such as tokenizing, parsing and analysis only ever reads snapshots.
 *
 * Equality is identity. Two snapshots are equal only if they are the same snapshot, not merely when their text is
 * equal, because comparing large texts would be O(n). To ask whether a result is still current, compare the [version]s
 * of snapshots of the same [lineage].
 *
 * @property text the complete text, with `\n` line separators only.
 * @property version increases monotonically per [EditableText], that is per [lineage], and starts at 0. Every applied
 *   change produces `version + 1`, including undo and redo.
 * @property lineage the text this snapshot is a version of. All snapshots of one [EditableText] share it. A snapshot
 *   created on its own gets its own lineage by default. See [TextLineage].
 */
public class TextSnapshot(
    public val text: String,
    public val version: Long,
    public val lineage: TextLineage = TextLineage(),
) {

    init {
        require(version >= 0) { "version must be >= 0, was $version" }
    }

    /** Mapping between lines and offsets for [text], computed on first use. */
    public val lineIndex: LineIndex by lazy { LineIndex(text) }

    /** Length of [text] in UTF-16 code units. */
    public val length: Int get() = text.length

    /** Number of lines, as counted by [LineIndex.lineCount]. */
    public val lineCount: Int get() = lineIndex.lineCount

    /** Character at [offset]. */
    public operator fun get(offset: Int): Char = text[offset]

    /** The text covered by [range]. */
    public fun substring(range: TextRange): String = text.substring(range.start, range.end)

    /** Range of [line] without its terminating `\n`. */
    public fun lineRange(line: Int): TextRange = TextRange(lineIndex.lineStart(line), lineIndex.lineEnd(line))

    /** Text of [line] without its terminating `\n`. */
    public fun lineText(line: Int): String = substring(lineRange(line))

    /** Position of [offset], as computed by [LineIndex.positionOf]. */
    public fun positionOf(offset: Int): TextPosition = lineIndex.positionOf(offset)

    /** Clamped offset of [position], as computed by [LineIndex.offsetOf]. */
    public fun offsetOf(position: TextPosition): Int = lineIndex.offsetOf(position)

    /** Clamped offset of [line] and [column], as computed by [LineIndex.offsetOf]. */
    public fun offsetOf(line: Int, column: Int): Int = lineIndex.offsetOf(line, column)

    override fun toString(): String = "TextSnapshot(version=$version, length=$length)"
}
