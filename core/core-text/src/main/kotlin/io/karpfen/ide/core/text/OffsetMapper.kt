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
 * Maps offsets across the edits of one [EditScript] in O(log n) per offset, where n is the number of edits. A version
 * made by one bulk action, such as indenting every line or Replace All, therefore costs every range consumer as little
 * as a single edit does.
 *
 * The mapping rules are those of [EditScript.mapOffset]. This class only indexes the sorted edits, which never overlap.
 * It keeps three things:
 * - the starts and the ends of the edits, both non-decreasing, for binary searches,
 * - the length delta of every prefix of the edits,
 * - for each replacement, where the run of adjacent replacements around it begins and ends. An offset inside a
 *   replaced range moves past the whole run, also across insertions at the shared bounds.
 *
 * The index is built once per script in O(n log n). It is immutable and therefore safe to share between threads.
 */
internal class OffsetMapper(edits: List<TextEdit>) {

    private val size = edits.size
    private val starts = IntArray(size) { edits[it].range.start }
    private val ends = IntArray(size) { edits[it].range.end }
    private val prefixDelta = IntArray(size + 1).also { delta ->
        for (i in 0 until size) delta[i + 1] = delta[i] + edits[i].lengthDelta
    }

    /** For each replacement, the end of the run of adjacent replacements that it starts, itself included. */
    private val runEnd = IntArray(size).also { run ->
        for (i in size - 1 downTo 0) {
            val next = lastStartingAt(ends[i])
            run[i] = if (isReplacement(i) && next > i && isReplacement(next)) run[next] else ends[i]
        }
    }

    /** For each replacement, the start of the run of adjacent replacements that it ends, itself included. */
    private val runStart = IntArray(size).also { run ->
        for (i in 0 until size) {
            val previous = firstEndingAt(starts[i])
            run[i] = if (isReplacement(i) && previous in 0 until i && isReplacement(previous)) run[previous] else starts[i]
        }
    }

    /**
     * [EditScript.mapOffset] with [Bias.RIGHT]. The offset moves past a run of replacements that contains [offset] and
     * past insertions at it.
     */
    fun mapRight(offset: Int): Int {
        val last = countStartingAtOrBefore(offset) - 1
        val anchor = if (last >= 0 && ends[last] > offset) runEnd[last] else offset
        return anchor + prefixDelta[countStartingAtOrBefore(anchor)]
    }

    /**
     * [EditScript.mapOffset] with [Bias.LEFT]. The offset moves to the start of a run of replacements that ends at or
     * contains [offset].
     */
    fun mapLeft(offset: Int): Int {
        val last = countStartingBefore(offset) - 1
        val anchor = if (last >= 0 && isReplacement(last) && ends[last] >= offset) runStart[last] else offset
        return anchor + prefixDelta[countStartingBefore(anchor)]
    }

    private fun isReplacement(index: Int): Boolean = ends[index] > starts[index]

    /** The number of edits that start at or before [offset]. */
    private fun countStartingAtOrBefore(offset: Int): Int = firstGreater(starts, offset)

    /** The number of edits that start before [offset]. */
    private fun countStartingBefore(offset: Int): Int = firstAtLeast(starts, offset)

    /**
     * The last edit that starts at [offset], or -1. If a replacement starts there, it sorts after the insertions at the
     * same offset.
     */
    private fun lastStartingAt(offset: Int): Int {
        val index = countStartingAtOrBefore(offset) - 1
        return if (index >= 0 && starts[index] == offset) index else -1
    }

    /**
     * The first edit that ends at [offset], or -1. If a replacement ends there, it sorts before the insertions at that
     * offset.
     */
    private fun firstEndingAt(offset: Int): Int {
        val index = firstAtLeast(ends, offset)
        return if (index < size && ends[index] == offset) index else -1
    }

    /** The first index of the sorted [values] whose value is greater than [value], or [size] if there is none. */
    private fun firstGreater(values: IntArray, value: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }

    /** The first index of the sorted [values] whose value is at least [value], or [size] if there is none. */
    private fun firstAtLeast(values: IntArray, value: Int): Int = firstGreater(values, value - 1)
}
