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

/**
 * The editor panes of a project as its project file stores them: the tree of panes and splits, the files that every
 * pane shows in tabs, the active tab of every pane and the active pane. It is the stored counterpart of the live
 * arrangement of the editor area, with the files named like every file of the project settings, relative to the
 * project folder with forward slashes. The settings know nothing of the workbench's panes, so the workbench converts
 * between the two when it writes and when it restores.
 *
 * An editor area that is not split is a [root] of one [Node.Pane]. That is what a project file without pane keys
 * holds, and what [EMPTY] holds without any tab.
 *
 * @property root the tree of panes.
 * @property activePane the index of the active pane in focus order, which is the order of [panes].
 */
public data class StoredPanes(public val root: Node, public val activePane: Int = 0) {

    /** The panes in focus order: depth first, the first part of a split before its second. */
    public val panes: List<Node.Pane> = root.panes()

    init {
        require(activePane in panes.indices) { "The active pane $activePane is not one of the ${panes.size} panes" }
    }

    /** Every file that a pane shows, each once, in focus order of the panes and tab order within a pane. */
    public val openFiles: List<String> get() = panes.flatMap { it.tabs }.distinct()

    /** The active tab of the active pane, or null if that pane names none. */
    public val activeFile: String? get() = panes[activePane].active

    /**
     * These panes without the panes that have no tab. A split with an empty part gives way to its other part. If no
     * pane has a tab, one empty pane is left. If the active pane disappears, the first remaining pane becomes active.
     */
    public fun withoutEmptyPanes(): StoredPanes {
        val kept = panes.map { it.tabs.isNotEmpty() }
        if (kept.none { it }) return EMPTY
        var index = 0
        val root = checkNotNull(root.pruned { kept[index++] })
        val active = if (kept[activePane]) kept.take(activePane).count { it } else 0
        return StoredPanes(root, active)
    }

    /** How a split arranges its two parts. */
    public enum class Orientation {
        /** Side by side: the first part on the left. */
        ROW,

        /** One above the other: the first part on top. */
        COLUMN,
    }

    /** A node of the tree of stored panes. */
    public sealed interface Node {

        /**
         * One pane.
         *
         * @property tabs the files of the pane's tabs in display order, each once.
         * @property active the file of the pane's active tab. Null means that the project file names none, and a
         *   restore then activates the pane's last tab.
         */
        public data class Pane(public val tabs: List<String> = emptyList(), public val active: String? = null) : Node {
            init {
                require(tabs.size == tabs.toSet().size) { "A pane shows each file in one tab only: $tabs" }
                require(active == null || active in tabs) { "The active tab $active is not among $tabs" }
            }
        }

        /**
         * Two parts next to each other or one above the other.
         *
         * @property orientation whether the parts are side by side or one above the other.
         * @property share the share of the space that the [first] part takes, greater than 0 and less than 1.
         * @property first the left or upper part.
         * @property second the right or lower part.
         */
        public data class Split(
            public val orientation: Orientation,
            public val share: Float,
            public val first: Node,
            public val second: Node,
        ) : Node {
            init {
                require(share > 0f && share < 1f) { "The share of a split lies between 0 and 1, was $share" }
            }
        }
    }

    public companion object {
        /** One pane without tabs, the editor area of a project that has no file open. */
        public val EMPTY: StoredPanes = StoredPanes(Node.Pane())

        /**
         * One pane with [tabs] and the tab of [active]. A file listed twice gets one tab, and an [active] file that is
         * not among the tabs counts as none.
         */
        public fun single(tabs: List<String>, active: String? = null): StoredPanes {
            val distinct = tabs.distinct()
            return StoredPanes(Node.Pane(distinct, active?.takeIf { it in distinct }))
        }
    }
}

/** The panes of this tree in focus order. */
private fun StoredPanes.Node.panes(): List<StoredPanes.Node.Pane> = when (this) {
    is StoredPanes.Node.Pane -> listOf(this)
    is StoredPanes.Node.Split -> first.panes() + second.panes()
}

/**
 * This tree with the panes for which [keep] answers false removed, or null if no pane is kept. [keep] is asked once
 * for every pane, in focus order.
 */
private fun StoredPanes.Node.pruned(keep: () -> Boolean): StoredPanes.Node? = when (this) {
    is StoredPanes.Node.Pane -> takeIf { keep() }
    is StoredPanes.Node.Split -> {
        val first = first.pruned(keep)
        val second = second.pruned(keep)
        when {
            first == null -> second
            second == null -> first
            else -> copy(first = first, second = second)
        }
    }
}
