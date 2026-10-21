package io.karpfen.ide.core.text

/**
 * Maps offsets across the edits of one [EditScript] in O(log n) per offset (n = the number of edits), so a version made
 * by one bulk action (indenting every line, Replace All) costs every range consumer as little as a single edit does.
 * The rules are [EditScript.mapOffset]'s; this class only indexes the sorted, non-overlapping edits:
 * - the starts and the ends of the edits, both non-decreasing, for binary searches;
 * - the length delta of every prefix of the edits;
 * - for each replacement, where a run of adjacent replacements around it begins and ends (an offset inside a replaced
 *   range moves past the whole run, also across insertions at the shared bounds).
 *
 * Built once per script (O(n log n)); immutable and therefore safe to share between threads.
 */
internal class OffsetMapper(edits: List<TextEdit>) {

    private val size = edits.size
    private val starts = IntArray(size) { edits[it].range.start }
    private val ends = IntArray(size) { edits[it].range.end }
    private val prefixDelta = IntArray(size + 1).also { delta ->
        for (i in 0 until size) delta[i + 1] = delta[i] + edits[i].lengthDelta
    }

    /** For a replacement: the end of the run of adjacent replacements it starts (itself included). */
    private val runEnd = IntArray(size).also { run ->
        for (i in size - 1 downTo 0) {
            val next = lastStartingAt(ends[i])
            run[i] = if (isReplacement(i) && next > i && isReplacement(next)) run[next] else ends[i]
        }
    }

    /** For a replacement: the start of the run of adjacent replacements it ends (itself included). */
    private val runStart = IntArray(size).also { run ->
        for (i in 0 until size) {
            val previous = firstEndingAt(starts[i])
            run[i] = if (isReplacement(i) && previous in 0 until i && isReplacement(previous)) run[previous] else starts[i]
        }
    }

    /** [EditScript.mapOffset] with [Bias.RIGHT]: past a run of replacements containing [offset] and past insertions at it. */
    fun mapRight(offset: Int): Int {
        val last = countStartingAtOrBefore(offset) - 1
        val anchor = if (last >= 0 && ends[last] > offset) runEnd[last] else offset
        return anchor + prefixDelta[countStartingAtOrBefore(anchor)]
    }

    /** [EditScript.mapOffset] with [Bias.LEFT]: to the start of a run of replacements that ends at or contains [offset]. */
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

    /** The last edit that starts at [offset] (the replacement, if one does, sorts after the insertions there), or -1. */
    private fun lastStartingAt(offset: Int): Int {
        val index = countStartingAtOrBefore(offset) - 1
        return if (index >= 0 && starts[index] == offset) index else -1
    }

    /** The first edit that ends at [offset] (the replacement, if one does, sorts before the insertions there), or -1. */
    private fun firstEndingAt(offset: Int): Int {
        val index = firstAtLeast(ends, offset)
        return if (index < size && ends[index] == offset) index else -1
    }

    /** The first index of the sorted [values] whose value is greater than [value] ([size] if none). */
    private fun firstGreater(values: IntArray, value: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= value) low = middle + 1 else high = middle
        }
        return low
    }

    /** The first index of the sorted [values] whose value is at least [value] ([size] if none). */
    private fun firstAtLeast(values: IntArray, value: Int): Int = firstGreater(values, value - 1)
}
