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
package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.TextLayoutResult

/**
 * A least recently used cache of the `TextLayoutResult` of each line. The key is the line text together with the
 * highlight spans and the font settings. So scrolling and caret blinking do not measure unchanged lines again. The
 * cache holds at most [capacity] layouts, and a lookup marks the entry as most recently used. Use it from the UI
 * thread, during composition and drawing.
 */
internal class LineLayoutCache(private val capacity: Int) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    /** The layouts in access order. Inserting beyond [capacity] evicts the least recently used one. */
    private val entries = object : LinkedHashMap<LineLayoutKey, TextLayoutResult>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LineLayoutKey, TextLayoutResult>?): Boolean =
            size > capacity
    }

    /** The number of cached layouts. */
    val size: Int get() = entries.size

    /** Returns the cached layout for [key], or null. */
    fun get(key: LineLayoutKey): TextLayoutResult? = entries[key]

    /** Stores [layout] for [key]. Beyond [capacity] it evicts the least recently used entry. */
    fun put(key: LineLayoutKey, layout: TextLayoutResult) {
        entries[key] = layout
    }

    /** The sizing of the backing access-ordered map. */
    private companion object {
        /** The initial size of the map, which grows up to the capacity. */
        const val INITIAL_CAPACITY = 64

        /** The load factor of the map, which is the JDK default. */
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * The cache key of one laid-out line.
 *
 * @property text the source text of the line.
 * @property spansHash a hash of the spans of the line and of the theme. A collision only costs one more measurement.
 * @property fontSize the font size in sp.
 * @property tabWidth the tab display width.
 */
internal data class LineLayoutKey(val text: String, val spansHash: Int, val fontSize: Float, val tabWidth: Int)
