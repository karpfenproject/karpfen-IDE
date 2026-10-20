package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.TextLayoutResult

/**
 * LRU cache of per-line `TextLayoutResult`s keyed by line text + highlight spans + font settings (spec §7.2), so
 * scrolling and caret blinking do not re-measure unchanged lines.
 */
internal class LineLayoutCache(private val capacity: Int) {

    /** The cached layout for [key], or null. */
    fun get(key: LineLayoutKey): TextLayoutResult? = TODO("WP-EDITOR-VIEW: LRU lookup")

    /** Stores [layout] for [key], evicting the least recently used entry beyond [capacity]. */
    fun put(key: LineLayoutKey, layout: TextLayoutResult): Unit = TODO("WP-EDITOR-VIEW: LRU insert")
}

/** Cache key of one laid-out line. */
internal data class LineLayoutKey(val text: String, val spansHash: Int, val fontSize: Float, val tabWidth: Int)
