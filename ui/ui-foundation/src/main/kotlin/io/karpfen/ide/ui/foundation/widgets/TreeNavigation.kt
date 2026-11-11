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
package io.karpfen.ide.ui.foundation.widgets

import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyStroke

/** The keys a [TreeView] handles (without modifiers). */
internal enum class TreeKey { UP, DOWN, LEFT, RIGHT, ENTER, HOME, END }

/** What a key press or a click asks the hoisted tree state to do. */
internal sealed interface TreeIntent<out K> {
    /** Select the node [key]. */
    data class Select<K>(val key: K) : TreeIntent<K>

    /** Expand or collapse the node [key]. */
    data class Toggle<K>(val key: K) : TreeIntent<K>

    /** Open the node [key]. */
    data class Open<K>(val key: K) : TreeIntent<K>

    /** A primary click on the row of [key] outside its chevron. It is reported even when the row is selected. */
    data class Click<K>(val key: K) : TreeIntent<K>
}

/**
 * The keyboard policy of a [TreeView], as pure functions. Up and Down move the selection. Home and End jump to the
 * first and the last row. Left collapses an expanded node or moves to the parent. Right expands a collapsed node or
 * moves to the first child. Enter opens the node. Without a visible selection, Up, Down and Home select the first row,
 * and End selects the last one.
 */
internal object TreeNavigation {

    /** The tree key of [stroke], or null for a stroke the tree does not handle, such as any stroke with a modifier. */
    fun keyOf(stroke: KeyStroke): TreeKey? {
        if (stroke.modifiers.isNotEmpty()) return null
        return when (stroke.key) {
            KeyCode.UP -> TreeKey.UP
            KeyCode.DOWN -> TreeKey.DOWN
            KeyCode.LEFT -> TreeKey.LEFT
            KeyCode.RIGHT -> TreeKey.RIGHT
            KeyCode.ENTER -> TreeKey.ENTER
            KeyCode.HOME -> TreeKey.HOME
            KeyCode.END -> TreeKey.END
            else -> null
        }
    }

    /**
     * The intent of [key] on [rows], where the nodes [expanded] are expanded and the row [selectedIndex] is selected.
     * [selectedIndex] is -1 when no row is selected. Returns null if the key does nothing there, so that the key event
     * is not consumed.
     */
    fun <K> intentFor(key: TreeKey, rows: List<TreeRow<K>>, expanded: Set<K>, selectedIndex: Int): TreeIntent<K>? {
        if (rows.isEmpty()) return null
        if (selectedIndex !in rows.indices) {
            return when (key) {
                TreeKey.UP, TreeKey.DOWN, TreeKey.HOME -> TreeIntent.Select(rows.first().node.key)
                TreeKey.END -> TreeIntent.Select(rows.last().node.key)
                else -> null
            }
        }
        val row = rows[selectedIndex]
        val isExpanded = row.isExpandable && row.node.key in expanded
        return when (key) {
            TreeKey.UP -> rows.getOrNull(selectedIndex - 1)?.let { TreeIntent.Select(it.node.key) }
            TreeKey.DOWN -> rows.getOrNull(selectedIndex + 1)?.let { TreeIntent.Select(it.node.key) }
            TreeKey.HOME -> selectOther(rows, selectedIndex, 0)
            TreeKey.END -> selectOther(rows, selectedIndex, rows.lastIndex)
            TreeKey.LEFT -> when {
                isExpanded -> TreeIntent.Toggle(row.node.key)
                row.parentIndex >= 0 -> TreeIntent.Select(rows[row.parentIndex].node.key)
                else -> null
            }
            TreeKey.RIGHT -> when {
                row.isExpandable && !isExpanded -> TreeIntent.Toggle(row.node.key)
                isExpanded && row.node.children.orEmpty().isNotEmpty() -> TreeIntent.Select(rows[selectedIndex + 1].node.key)
                else -> null
            }
            TreeKey.ENTER -> TreeIntent.Open(row.node.key)
        }
    }

    /** Selects the row [target]. Returns null when it is the selected row already, so the key does nothing. */
    private fun <K> selectOther(rows: List<TreeRow<K>>, selectedIndex: Int, target: Int): TreeIntent<K>? =
        if (target == selectedIndex) null else TreeIntent.Select(rows[target].node.key)
}
