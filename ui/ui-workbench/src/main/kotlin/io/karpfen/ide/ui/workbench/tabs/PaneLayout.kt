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
package io.karpfen.ide.ui.workbench.tabs

import java.nio.file.Path

/**
 * The identity of an editor pane. A pane keeps its id from its creation until it disappears, so views and intents can
 * name a pane while the tree around it changes.
 */
@JvmInline
public value class PaneId(public val value: Int)

/** The identity of a split between two parts of the editor area. A resize names the split by it. */
@JvmInline
public value class SplitId(public val value: Int)

/** How a split arranges its two parts. */
public enum class SplitOrientation {
    /** Side by side: the first part on the left, the second on the right. */
    ROW,

    /** One above the other: the first part on top, the second below. */
    COLUMN,
}

/**
 * The side of a pane on which a split puts the new pane.
 *
 * @property orientation the orientation of the split that the new pane and the split pane form.
 * @property placesFirst true if the new pane becomes the first part of that split, which is the left or the upper one.
 */
public enum class SplitSide(public val orientation: SplitOrientation, public val placesFirst: Boolean) {
    /** Split Left: the new pane goes to the left. */
    LEFT(SplitOrientation.ROW, placesFirst = true),

    /** Split Right: the new pane goes to the right. */
    RIGHT(SplitOrientation.ROW, placesFirst = false),

    /** Split Up: the new pane goes on top. */
    UP(SplitOrientation.COLUMN, placesFirst = true),

    /** Split Down: the new pane goes below. */
    DOWN(SplitOrientation.COLUMN, placesFirst = false),
}

/**
 * A node of the tree of editor panes. Inner nodes are [Split]s with exactly two parts, and the leaves are the [Pane]s.
 * The tree is an immutable value. Every change builds a new tree, so a view can compare two trees and a persister can
 * store one as it is.
 */
public sealed interface PaneNode {

    /**
     * One editor pane with its tabs.
     *
     * @property id the pane's identity.
     * @property tabs the files the pane shows in tabs, in display order. A file has at most one tab in a pane.
     * @property active the file of the pane's active tab. It is one of [tabs], or null exactly when the pane has no
     *   tab.
     */
    public data class Pane(
        public val id: PaneId,
        public val tabs: List<Path> = emptyList(),
        public val active: Path? = tabs.lastOrNull(),
    ) : PaneNode {
        init {
            require(tabs.size == tabs.toSet().size) { "A pane shows each file in one tab only: $tabs" }
            require(if (tabs.isEmpty()) active == null else active in tabs) { "The active tab $active is not among $tabs" }
        }
    }

    /**
     * Two parts of the editor area next to each other or one above the other.
     *
     * @property id the split's identity.
     * @property orientation whether the parts are side by side or one above the other.
     * @property share the share of the space that the [first] part takes, greater than 0 and less than 1. The second
     *   part takes the rest.
     * @property first the left or upper part.
     * @property second the right or lower part.
     */
    public data class Split(
        public val id: SplitId,
        public val orientation: SplitOrientation,
        public val share: Float,
        public val first: PaneNode,
        public val second: PaneNode,
    ) : PaneNode {
        init {
            require(share > 0f && share < 1f) { "The share of a split lies between 0 and 1, was $share" }
        }
    }
}

/**
 * The arrangement of the editor area: the tree of panes in [root] and the pane that is active. The active pane is the
 * one that was clicked or focused last. The commands of the workbench act on its active tab.
 *
 * The value holds everything that describes the arrangement apart from the editors and documents behind the tabs: the
 * orientation and share of every split, and the tabs and the active tab of every pane. It is what a persister stores
 * and what a restore starts from. The transformations below are pure functions that return a new layout. Only
 * [EditorTabsState] applies them to the live arrangement, together with the editors of the tabs.
 *
 * The panes are ordered depth first, the first part of a split before its second. That order is the focus order of
 * [next] and [previous] and the order in which [panes] lists them.
 *
 * @property root the tree of panes. It is a single [PaneNode.Pane] while the editor area is not split.
 * @property activePane the id of the active pane, which is one of the panes of [root].
 */
public data class PaneLayout(public val root: PaneNode, public val activePane: PaneId) {

    /** The panes in focus order. */
    public val panes: List<PaneNode.Pane> = root.panes()

    init {
        val ids = panes.map { it.id.value } + root.splits().map { it.id.value }
        require(ids.size == ids.toSet().size) { "The ids of the panes and splits are unique: $ids" }
        require(panes.any { it.id == activePane }) { "The active pane $activePane is not in the layout" }
    }

    /** The active pane. */
    public val active: PaneNode.Pane get() = panes.first { it.id == activePane }

    /** The pane with [id], or null if the layout has no such pane. */
    public fun pane(id: PaneId): PaneNode.Pane? = panes.firstOrNull { it.id == id }

    /** The ids of the panes that have a tab of [path], in focus order. */
    public fun panesShowing(path: Path): List<PaneId> = panes.filter { path in it.tabs }.map { it.id }

    /** The pane after [id] in focus order. After the last pane comes the first. An unknown id gives the active pane. */
    public fun next(id: PaneId): PaneId = neighbourInFocusOrder(id, step = 1)

    /** The pane before [id] in focus order. Before the first pane comes the last. An unknown id gives the active one. */
    public fun previous(id: PaneId): PaneId = neighbourInFocusOrder(id, step = -1)

    /** The greatest id of a pane or a split in the layout. New ids are chosen above it. */
    public val greatestId: Int get() = (panes.map { it.id.value } + root.splits().map { it.id.value }).max()

    /** This layout with [id] as the active pane. An unknown id changes nothing. */
    internal fun withActivePane(id: PaneId): PaneLayout = if (pane(id) == null) this else copy(activePane = id)

    /**
     * This layout with a tab of [path] in the pane [id] at [index], clamped to the pane's tabs, and that tab active in
     * the pane. A pane that has the tab already only activates it. Which pane is active does not change.
     */
    internal fun withTab(id: PaneId, path: Path, index: Int): PaneLayout = changingPane(id) { pane ->
        val tabs = if (path in pane.tabs) pane.tabs else pane.tabs.toMutableList().apply { add(index.coerceIn(0, size), path) }
        pane.copy(tabs = tabs, active = path)
    }

    /** This layout with the tab of [path] active in the pane [id]. A pane without such a tab does not change. */
    internal fun withActiveTab(id: PaneId, path: Path): PaneLayout =
        changingPane(id) { pane -> if (path in pane.tabs) pane.copy(active = path) else pane }

    /**
     * This layout without the tab of [path] in the pane [id]. If it was the pane's active tab, its right neighbour
     * becomes active, or its left neighbour if it was the last tab.
     *
     * A pane that loses its last tab disappears unless it is the only pane. The other part of its split then takes the
     * place of the split. If the pane was the active one, the pane of that part that lay next to it becomes active:
     * the first pane of the part for a pane that was on the left or on top, and the last pane of the part otherwise.
     * The only pane stays, empty.
     */
    internal fun withoutTab(id: PaneId, path: Path): PaneLayout {
        val pane = pane(id) ?: return this
        val index = pane.tabs.indexOf(path)
        if (index < 0) return this
        val remaining = pane.tabs.minusElement(path)
        if (remaining.isEmpty() && root is PaneNode.Split) return withoutPane(id)
        val active = if (pane.active == path) remaining.getOrNull(index) ?: remaining.getOrNull(index - 1) else pane.active
        return changingPane(id) { it.copy(tabs = remaining, active = active) }
    }

    /** This layout with the tab at [from] of the pane [id] moved to [to]. Indices outside the tabs change nothing. */
    internal fun withMovedTab(id: PaneId, from: Int, to: Int): PaneLayout = changingPane(id) { pane ->
        if (from !in pane.tabs.indices || to !in pane.tabs.indices || from == to) {
            pane
        } else {
            pane.copy(tabs = pane.tabs.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    /**
     * This layout with the pane [id] split towards [side]. The pane and a new pane [newPane] share the space of the
     * pane half and half in a new split [split], with the new pane on [side]. The new pane has one tab, the file of the
     * active tab of the pane [id], and it becomes the active pane. A pane without a tab cannot be split, and neither can
     * a pane that lies in [MAX_SPLIT_DEPTH] splits already; the layout then stays as it is.
     */
    internal fun split(id: PaneId, side: SplitSide, newPane: PaneId, split: SplitId): PaneLayout {
        val path = pane(id)?.active ?: return this
        if ((root.depthOf(id) ?: return this) >= MAX_SPLIT_DEPTH) return this
        val fresh = PaneNode.Pane(newPane, listOf(path), path)
        val root = root.rebuilt { node ->
            if (node !is PaneNode.Pane || node.id != id) {
                node
            } else {
                val (first, second) = if (side.placesFirst) fresh to node else node to fresh
                PaneNode.Split(split, side.orientation, HALF, first, second)
            }
        }
        return PaneLayout(root, newPane)
    }

    /** This layout with the share of the split [id] set to [share], kept between [MIN_SHARE] and [MAX_SHARE]. */
    internal fun resized(id: SplitId, share: Float): PaneLayout {
        val bounded = share.coerceIn(MIN_SHARE, MAX_SHARE)
        return copy(root = root.rebuilt { node -> if (node is PaneNode.Split && node.id == id) node.copy(share = bounded) else node })
    }

    /**
     * This layout with every tab of [old] showing [new] instead, at the same place, as after a rename. A pane that has
     * a tab of [new] already keeps that tab and loses the tab of [old].
     */
    internal fun withPathReplaced(old: Path, new: Path): PaneLayout = copy(
        root = root.rebuilt { node ->
            if (node !is PaneNode.Pane || old !in node.tabs || old == new) {
                node
            } else {
                val tabs = if (new in node.tabs) node.tabs.minusElement(old) else node.tabs.map { if (it == old) new else it }
                node.copy(tabs = tabs, active = node.active?.let { if (it == old) new else it })
            }
        },
    )

    /** This layout with the same panes and splits, every pane without tabs. A restore fills the panes again. */
    internal fun emptied(): PaneLayout =
        copy(root = root.rebuilt { node -> if (node is PaneNode.Pane) PaneNode.Pane(node.id) else node })

    /**
     * This layout without its empty panes, each split with an empty part replaced by its other part. If every pane is
     * empty, the active pane stays alone. If the active pane disappears, the first remaining pane becomes active.
     */
    internal fun withoutEmptyPanes(): PaneLayout {
        val root = root.pruned() ?: PaneNode.Pane(activePane)
        val panes = root.panes()
        return PaneLayout(root, if (panes.any { it.id == activePane }) activePane else panes.first().id)
    }

    /** This layout without the pane [id], which is part of a split. */
    private fun withoutPane(id: PaneId): PaneLayout {
        var neighbour = activePane
        val root = root.rebuilt { node ->
            when {
                node !is PaneNode.Split -> node
                node.first.isPane(id) -> node.second.also { neighbour = it.panes().first().id }
                node.second.isPane(id) -> node.first.also { neighbour = it.panes().last().id }
                else -> node
            }
        }
        return PaneLayout(root, if (activePane == id) neighbour else activePane)
    }

    /** This layout with the pane [id] replaced by [change] of it. */
    private fun changingPane(id: PaneId, change: (PaneNode.Pane) -> PaneNode.Pane): PaneLayout =
        copy(root = root.rebuilt { node -> if (node is PaneNode.Pane && node.id == id) change(node) else node })

    /** The pane [step] places away from [id] in focus order, cycling at both ends. */
    private fun neighbourInFocusOrder(id: PaneId, step: Int): PaneId {
        val index = panes.indexOfFirst { it.id == id }
        if (index < 0) return activePane
        return panes[(index + step).mod(panes.size)].id
    }

    public companion object {
        /** The smallest share of a split. A split is never resized below it, so neither part vanishes. */
        public const val MIN_SHARE: Float = 0.05f

        /** The greatest share of a split. */
        public const val MAX_SHARE: Float = 1f - MIN_SHARE

        /**
         * The greatest number of splits that lie around a pane. A pane that deep is split no further. Every split
         * halves a pane, so a pane this deep is far too small to see, and the project file reads trees nested far
         * deeper than this, so every layout made in the IDE can be stored and read again.
         */
        public const val MAX_SPLIT_DEPTH: Int = 64

        /** The share of a new split. */
        private const val HALF: Float = 0.5f

        /**
         * A layout of one pane [id] with [tabs] and the tab of [active]. A file listed twice gets one tab. If [active]
         * is not among the tabs, the last tab is active. This is the layout of an editor area that is not split.
         */
        public fun single(id: PaneId, tabs: List<Path> = emptyList(), active: Path? = null): PaneLayout {
            val distinct = tabs.distinct()
            return PaneLayout(PaneNode.Pane(id, distinct, active?.takeIf { it in distinct } ?: distinct.lastOrNull()), id)
        }
    }
}

/** The panes of this tree in focus order. */
private fun PaneNode.panes(): List<PaneNode.Pane> = when (this) {
    is PaneNode.Pane -> listOf(this)
    is PaneNode.Split -> first.panes() + second.panes()
}

/** The splits of this tree, parents before their parts. */
private fun PaneNode.splits(): List<PaneNode.Split> = when (this) {
    is PaneNode.Pane -> emptyList()
    is PaneNode.Split -> listOf(this) + first.splits() + second.splits()
}

/** The number of splits around the pane [id] in this tree, or null if the tree has no such pane. */
private fun PaneNode.depthOf(id: PaneId): Int? = when (this) {
    is PaneNode.Pane -> if (this.id == id) 0 else null
    is PaneNode.Split -> (first.depthOf(id) ?: second.depthOf(id))?.plus(1)
}

/** True if this node is the pane [id]. */
private fun PaneNode.isPane(id: PaneId): Boolean = this is PaneNode.Pane && this.id == id

/**
 * This tree rebuilt from the leaves up. Every node passes through [change] after its parts were rebuilt, so [change]
 * sees the new parts of a split.
 */
private fun PaneNode.rebuilt(change: (PaneNode) -> PaneNode): PaneNode = change(
    when (this) {
        is PaneNode.Pane -> this
        is PaneNode.Split -> copy(first = first.rebuilt(change), second = second.rebuilt(change))
    },
)

/** This tree without its empty panes, or null if every pane of it is empty. */
private fun PaneNode.pruned(): PaneNode? = when (this) {
    is PaneNode.Pane -> takeIf { it.tabs.isNotEmpty() }
    is PaneNode.Split -> {
        val first = first.pruned()
        val second = second.pruned()
        when {
            first == null -> second
            second == null -> first
            else -> copy(first = first, second = second)
        }
    }
}
