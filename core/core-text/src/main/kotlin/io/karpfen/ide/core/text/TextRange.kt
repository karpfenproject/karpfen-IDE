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
 * A half-open range `[start, end)` of UTF-16 code-unit offsets into a document text.
 *
 * Invariant: `0 <= start <= end`. An empty range (`start == end`) denotes a position between two characters, e.g. an
 * insertion point. Ranges are only meaningful together with the text version (and [TextLineage]) they were computed
 * for; [EditableText.mapRange] carries them across edits (the one range-mapping rule; never map the two bounds
 * yourself).
 */
public data class TextRange(public val start: Int, public val end: Int) : Comparable<TextRange> {

    init {
        require(start >= 0) { "start must be >= 0, was $start" }
        require(start <= end) { "start must be <= end, was [$start, $end)" }
    }

    /** Number of code units covered by this range. */
    public val length: Int get() = end - start

    /** True if the range covers no characters. */
    public val isEmpty: Boolean get() = start == end

    /** True if [offset] lies inside the half-open range (`start <= offset < end`); always false for an empty range. */
    public operator fun contains(offset: Int): Boolean = offset in start until end

    /**
     * True if [offset] lies inside the range or on one of its bounds (`start <= offset <= end`). Caret-oriented
     * lookups use this form so that a caret directly after a name still counts as "on" the name.
     */
    public fun containsInclusive(offset: Int): Boolean = offset in start..end

    /** True if [other] lies completely inside this range (an empty [other] may sit on either bound). */
    public operator fun contains(other: TextRange): Boolean = other.start >= start && other.end <= end

    /** True if both ranges share at least one character. Empty ranges never overlap anything. */
    public fun overlaps(other: TextRange): Boolean =
        !isEmpty && !other.isEmpty && start < other.end && other.start < end

    /** This range moved by [delta] code units. */
    public fun shiftedBy(delta: Int): TextRange = TextRange(start + delta, end + delta)

    /** The smallest range covering this range and [other]. */
    public fun union(other: TextRange): TextRange = TextRange(minOf(start, other.start), maxOf(end, other.end))

    /** Orders by [start], then by [end]. */
    override fun compareTo(other: TextRange): Int =
        if (start != other.start) start.compareTo(other.start) else end.compareTo(other.end)

    override fun toString(): String = "[$start, $end)"

    public companion object {
        /** The empty range at offset 0. */
        public val EMPTY: TextRange = TextRange(0, 0)

        /** The empty range (insertion point) at [offset]. */
        public fun empty(offset: Int): TextRange = TextRange(offset, offset)

        /** The range `[start, start + length)`. */
        public fun ofLength(start: Int, length: Int): TextRange = TextRange(start, start + length)
    }
}
