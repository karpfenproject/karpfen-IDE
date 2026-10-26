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
package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.text.TextRange

/**
 * Answers "does a range overlap any of [ranges]?" in O(log n) per query (`TextRange.overlaps` semantics: empty ranges
 * overlap nothing). Works for any input order and for overlapping [ranges]: they are sorted by start, and a prefix
 * maximum of their ends finds an overlap among all ranges starting before the query's end. Immutable.
 */
internal class RangeOverlapIndex(ranges: List<TextRange>) {

    private val sorted: List<TextRange> = ranges.filterNot { it.isEmpty }.sortedBy { it.start }
    private val maxEndUpTo: IntArray = IntArray(sorted.size).also { maxEnds ->
        var max = Int.MIN_VALUE
        sorted.forEachIndexed { index, range ->
            max = maxOf(max, range.end)
            maxEnds[index] = max
        }
    }

    /** True if [range] shares at least one character with one of the indexed ranges. */
    fun overlapsAny(range: TextRange): Boolean {
        if (range.isEmpty || sorted.isEmpty()) return false
        val last = lastStartingBefore(range.end)
        return last >= 0 && maxEndUpTo[last] > range.start
    }

    /** Index of the last range whose start is before [offset], or -1. */
    private fun lastStartingBefore(offset: Int): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].start < offset) low = mid + 1 else high = mid
        }
        return low - 1
    }
}
