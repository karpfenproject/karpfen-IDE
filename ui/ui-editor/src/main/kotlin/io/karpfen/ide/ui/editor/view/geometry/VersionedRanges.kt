package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.core.text.TextRange

/**
 * Finds the items of a sorted, non-overlapping list of ranges (highlight spans, find matches) that intersect a window
 * of the drawn text, after mapping each range into the drawn version with [map] — `EditableText.mapRange`, the one
 * range-mapping rule, or the identity when the versions agree. Mapping keeps
 * the order (start `RIGHT`, end `LEFT`), so the first candidate is found by binary search and only the items near the
 * window are mapped: drawing an old version's spans costs O(log n + visible) per frame, not O(n).
 *
 * Ranges that do not map (another lineage, evicted history) or collapse to empty are left out (decorations never grow
 * over text that was not there).
 */
internal class VersionedRanges<T>(
    private val items: List<T>,
    private val rangeOf: (T) -> TextRange,
    private val map: (TextRange) -> TextRange?,
) {

    /** An item with its range in the drawn version. */
    data class Mapped<T>(val item: T, val range: TextRange)

    /** The items whose mapped range intersects [window] (or touches it, for empty windows), in order. */
    fun intersecting(window: TextRange): List<Mapped<T>> {
        if (items.isEmpty()) return emptyList()
        if (map(rangeOf(items.first())) == null) return emptyList()
        var low = 0
        var high = items.size
        while (low < high) {
            val middle = (low + high) ushr 1
            val mapped = map(rangeOf(items[middle]))
            if (mapped == null || mapped.end <= window.start) low = middle + 1 else high = middle
        }
        val result = ArrayList<Mapped<T>>()
        var index = low
        while (index < items.size) {
            val mapped = map(rangeOf(items[index]))
            if (mapped != null) {
                if (mapped.start >= window.end && !(window.isEmpty && mapped.start == window.start)) break
                if (!mapped.isEmpty) result += Mapped(items[index], mapped)
            }
            index++
        }
        return result
    }
}
