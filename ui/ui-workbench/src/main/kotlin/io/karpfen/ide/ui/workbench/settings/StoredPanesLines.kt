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
package io.karpfen.ide.ui.workbench.settings

import kotlin.math.roundToInt

/**
 * The lines of the project file that hold the [StoredPanes]. An editor area that is not split is written as the lines
 * `open` and `active`, one `open` line per tab in the order of the tabs:
 *
 * ```
 * open = lamp.kstates
 * open = lamp.kmeta
 * active = lamp.kstates
 * ```
 *
 * A split editor area numbers its panes from 1 in focus order, which is left before right and top before bottom. The
 * line `split` gives the tree, every pane has its own `open.<number>` and `active.<number>` lines, and `activePane`
 * names the active pane:
 *
 * ```
 * split = row 50 (1, column 40 (2, 3))
 * open.1 = lamp.kstates
 * open.1 = lamp.kmeta
 * active.1 = lamp.kmeta
 * open.2 = lamp.kstates
 * active.2 = lamp.kstates
 * open.3 = lamp.kmodel
 * active.3 = lamp.kmodel
 * activePane = 2
 * ```
 *
 * A node of the tree is either the number of a pane or a split: `row` for two parts side by side or `column` for two
 * parts one above the other, the share of the first part in percent, and the two parts in parentheses, separated by a
 * comma. The share has at most one decimal. The keys cannot be mistaken for the keys `pane.<name>` of the tool windows.
 *
 * Reading never fails and takes what can be read. The lines `open` and `active` without a number belong to pane 1, as
 * `open.1` and `active.1` do. A share that is not a number between 0 and 100 counts as 50. A tree that cannot be read,
 * that names a pane twice or that misses a pane with `open` lines falls back to one pane with every tab of every pane,
 * in the order of the pane numbers. Its active tab is that of the active pane, or else that of pane 1. A pane without
 * tabs is dropped, and the other part of its split takes its place. An `activePane` that names no remaining pane counts
 * as the first. An `active` line that names a file that is not open in its pane counts as none, and so does an
 * `activePane` that is not a number. `open` and `active` lines with a number that is not a positive whole number are
 * not pane lines, so the project file keeps them as lines it does not understand.
 *
 * One reader collects the lines of one file with [read] and gives the panes with [panes]. [format] writes them.
 */
internal class StoredPanesLines {

    private val tabs = sortedMapOf<Int, MutableList<String>>()
    private val actives = HashMap<Int, String>()
    private var tree: String? = null
    private var activeNumber: Int? = null

    /**
     * Takes the line `[key] = [value]` if its key is one of the pane keys and answers true, or answers false and takes
     * nothing for any other key. An empty value is taken and means nothing.
     */
    fun read(key: String, value: String): Boolean {
        when {
            key == OPEN -> addTab(FIRST_PANE, value)
            key == ACTIVE -> setActive(FIRST_PANE, value)
            key == SPLIT -> tree = value
            key == ACTIVE_PANE -> activeNumber = value.toIntOrNull()?.takeIf { it > 0 }
            else -> {
                numbered(key, OPEN)?.let { addTab(it, value) }
                    ?: numbered(key, ACTIVE)?.let { setActive(it, value) }
                    ?: return false
            }
        }
        return true
    }

    /** The panes that the lines read so far describe, as the class describes. */
    fun panes(): StoredPanes {
        val shape = tree?.let(::shapeOf)?.takeIf { shape ->
            val numbers = shape.numbers()
            numbers.size == numbers.toSet().size && numbers.containsAll(tabs.keys)
        } ?: return fallback()
        val active = shape.numbers().indexOf(activeNumber).coerceAtLeast(0)
        return StoredPanes(shape.toNode(::pane), active).withoutEmptyPanes()
    }

    /** One pane with every tab of every pane, as the class describes. */
    private fun fallback(): StoredPanes {
        val all = tabs.values.flatten()
        val active = (listOfNotNull(activeNumber) + FIRST_PANE).firstNotNullOfOrNull { number -> actives[number]?.takeIf { it in all } }
        return StoredPanes.single(all, active)
    }

    /** The pane that the lines of [number] describe. */
    private fun pane(number: Int): StoredPanes.Node.Pane {
        val paneTabs = tabs[number].orEmpty().toList()
        return StoredPanes.Node.Pane(paneTabs, actives[number]?.takeIf { it in paneTabs })
    }

    /** Adds the tab [value] to the pane [number]. An empty value names no pane, so it cannot make the tree fall back. */
    private fun addTab(number: Int, value: String) {
        if (value.isEmpty()) return
        val list = tabs.getOrPut(number) { ArrayList() }
        if (value !in list) list.add(value)
    }

    private fun setActive(number: Int, value: String) {
        if (value.isNotEmpty()) actives[number] = value
    }

    /** The number after `[prefix].` in [key], or null if [key] is not of that form with a positive whole number. */
    private fun numbered(key: String, prefix: String): Int? =
        if (key.startsWith("$prefix.")) key.removePrefix("$prefix.").toIntOrNull()?.takeIf { it > 0 } else null

    /** The tree of pane numbers that [text] describes, or null if it cannot be read as a whole. */
    private fun shapeOf(text: String): Shape? {
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        val parser = ShapeParser(tokens)
        val shape = parser.node(depth = 0) ?: return null
        return shape.takeIf { parser.atEnd }
    }

    /** A tree of pane numbers, as the line `split` writes it, before the numbers are replaced by their panes. */
    private sealed interface Shape {
        data class Leaf(val number: Int) : Shape

        data class Branch(val orientation: StoredPanes.Orientation, val share: Float, val first: Shape, val second: Shape) : Shape

        /** The pane numbers in focus order. */
        fun numbers(): List<Int> = when (this) {
            is Leaf -> listOf(number)
            is Branch -> first.numbers() + second.numbers()
        }

        /** The tree of panes with every number replaced by [pane] of it. */
        fun toNode(pane: (Int) -> StoredPanes.Node.Pane): StoredPanes.Node = when (this) {
            is Leaf -> pane(number)
            is Branch -> StoredPanes.Node.Split(orientation, share, first.toNode(pane), second.toNode(pane))
        }
    }

    /**
     * Reads a tree from [tokens] by recursive descent. A node is a positive pane number, or an orientation, an
     * optional share and two nodes in parentheses separated by a comma. Nesting deeper than [MAX_DEPTH] is refused, so
     * a hand-made line cannot exhaust the stack. The limit lies far above the 64 splits around a pane that the IDE makes
     * at most, so every tree the IDE writes is read again.
     */
    private class ShapeParser(private val tokens: List<String>) {
        private var position = 0

        /** True when every token was read. */
        val atEnd: Boolean get() = position == tokens.size

        fun node(depth: Int): Shape? {
            if (depth > MAX_DEPTH) return null
            val token = next() ?: return null
            token.toIntOrNull()?.let { return if (it > 0) Shape.Leaf(it) else null }
            val orientation = StoredPanes.Orientation.entries.firstOrNull { it.name.equals(token, ignoreCase = true) } ?: return null
            val share = when (val word = peek()) {
                null -> return null
                OPEN_PAREN -> HALF
                else -> shareOf(word).also { position++ }
            }
            if (next() != OPEN_PAREN) return null
            val first = node(depth + 1) ?: return null
            if (next() != COMMA) return null
            val second = node(depth + 1) ?: return null
            if (next() != CLOSE_PAREN) return null
            return Shape.Branch(orientation, share, first, second)
        }

        private fun peek(): String? = tokens.getOrNull(position)

        private fun next(): String? = tokens.getOrNull(position)?.also { position++ }

        /** The share that the percent [token] gives, or a half if it is no number between 0 and 100. */
        private fun shareOf(token: String): Float =
            token.toFloatOrNull()?.div(PERCENT)?.takeIf { it > 0f && it < 1f } ?: HALF
    }

    companion object {
        private const val OPEN = "open"
        private const val ACTIVE = "active"
        private const val SPLIT = "split"
        private const val ACTIVE_PANE = "activePane"
        private const val FIRST_PANE = 1
        private const val HALF = 0.5f
        private const val PERCENT = 100f
        private const val TENTHS_PER_WHOLE = 1000
        private const val TENTHS_PER_PERCENT = 10
        private const val MAX_DEPTH = 1000
        private const val OPEN_PAREN = "("
        private const val CLOSE_PAREN = ")"
        private const val COMMA = ","
        private val TOKEN = Regex("""[(),]|[^\s(),]+""")

        /** The lines that hold [panes], in the order the class shows. */
        fun format(panes: StoredPanes): List<String> = buildList {
            val root = panes.root
            if (root is StoredPanes.Node.Pane) {
                root.tabs.forEach { add("$OPEN = $it") }
                root.active?.let { add("$ACTIVE = $it") }
                return@buildList
            }
            var counter = 0
            add("$SPLIT = ${treeText(root) { ++counter }}")
            panes.panes.forEachIndexed { index, pane ->
                val number = index + 1
                pane.tabs.forEach { add("$OPEN.$number = $it") }
                pane.active?.let { add("$ACTIVE.$number = $it") }
            }
            add("$ACTIVE_PANE = ${panes.activePane + 1}")
        }

        /** The text of the tree [node], with the panes numbered by [number] in focus order. */
        private fun treeText(node: StoredPanes.Node, number: () -> Int): String = when (node) {
            is StoredPanes.Node.Pane -> number().toString()
            is StoredPanes.Node.Split -> {
                val first = treeText(node.first, number)
                val second = treeText(node.second, number)
                "${node.orientation.name.lowercase()} ${percent(node.share)} ($first, $second)"
            }
        }

        /** [share] in percent with at most one decimal, kept above 0 and below 100. */
        private fun percent(share: Float): String {
            val tenths = (share * TENTHS_PER_WHOLE).roundToInt().coerceIn(1, TENTHS_PER_WHOLE - 1)
            val whole = tenths / TENTHS_PER_PERCENT
            val rest = tenths % TENTHS_PER_PERCENT
            return if (rest == 0) "$whole" else "$whole.$rest"
        }
    }
}
