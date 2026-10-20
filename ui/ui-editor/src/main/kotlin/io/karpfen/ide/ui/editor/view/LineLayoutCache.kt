package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.TextLayoutResult

/**
 * LRU cache of per-line `TextLayoutResult`s keyed by line text + highlight spans + font settings (spec §7.2), so
 * scrolling and caret blinking do not re-measure unchanged lines. Holds at most [capacity] layouts; a lookup marks the
 * entry as most recently used. Used from the UI thread (composition and drawing).
 */
internal class LineLayoutCache(private val capacity: Int) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

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

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}

/** Cache key of one laid-out line. */
internal data class LineLayoutKey(val text: String, val spansHash: Int, val fontSize: Float, val tabWidth: Int)
