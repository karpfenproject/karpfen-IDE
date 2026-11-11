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
package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.core.text.TextRange

/**
 * Finds the items of a sorted list of ranges that intersect a window of the drawn text. The ranges must not overlap.
 * Examples are highlight spans and find matches. Each range is first mapped into the drawn version with [map]. That is
 * `EditableText.mapRange`, the single range-mapping rule, or the identity when the versions agree.
 *
 * Mapping keeps the order, because it maps the start with bias `RIGHT` and the end with bias `LEFT`. So the first
 * candidate is found by binary search, and only the items near the window are mapped. Drawing the spans of an old
 * version therefore costs O(log n + visible) per frame and not O(n).
 *
 * Ranges that do not map are left out. That happens for another lineage or for history that was evicted. Ranges that
 * collapse to empty are left out too. So decorations never grow over text that was not there.
 */
internal class VersionedRanges<T>(
    private val items: List<T>,
    private val rangeOf: (T) -> TextRange,
    private val map: (TextRange) -> TextRange?,
) {

    /** An item with its range in the drawn version. */
    data class Mapped<T>(val item: T, val range: TextRange)

    /** Returns the items whose mapped range intersects [window], in order. For an empty window, touching it is enough. */
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
