package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.TextLayoutResult

/**
 * LRU cache of per-line `TextLayoutResult`s keyed by line text + highlight spans + font settings, so
 * scrolling and caret blinking do not re-measure unchanged lines. Holds at most [capacity] layouts; a lookup marks the
 * entry as most recently used. Used from the UI thread (composition and drawing).
 */
internal class LineLayoutCache(private val capacity: Int) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    /** The layouts in access order; inserting beyond [capacity] evicts the least recently used one. */
    private val entries = object : LinkedHashMap<LineLayoutKey, TextLayoutResult>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LineLayoutKey, TextLayoutResult>?): Boolean =
            size > capacity
    }

    /** Number of cached layouts. */
    val size: Int get() = entries.size

    /** The cached layout for [key], or null. */
    fun get(key: LineLayoutKey): TextLayoutResult? = entries[key]

    /** Stores [layout] for [key], evicting the least recently used entry beyond [capacity]. */
    fun put(key: LineLayoutKey, layout: TextLayoutResult) {
        entries[key] = layout
    }

    /** The sizing of the backing access-ordered map. */
    private companion object {
        /** Initial size of the map, which grows up to the capacity. */
        const val INITIAL_CAPACITY = 64

        /** The map's load factor (the JDK default). */
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Cache key of one laid-out line.
 *
 * @property text the line's source text.
 * @property spansHash a hash of the line's spans and theme; a collision only costs a re-measure.
 * @property fontSize the font size in sp.
 * @property tabWidth the tab display width.
 */
internal data class LineLayoutKey(val text: String, val spansHash: Int, val fontSize: Float, val tabWidth: Int)
