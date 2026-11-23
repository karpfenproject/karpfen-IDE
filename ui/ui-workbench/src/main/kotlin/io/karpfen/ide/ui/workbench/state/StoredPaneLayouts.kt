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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.settings.StoredPanes
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.PaneLayout
import io.karpfen.ide.ui.workbench.tabs.PaneNode
import io.karpfen.ide.ui.workbench.tabs.SplitId
import io.karpfen.ide.ui.workbench.tabs.SplitOrientation
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Converts between the live arrangement of the editor panes, a [PaneLayout] with absolute paths, and the [StoredPanes]
 * of the project file, with files named relative to the project folder with forward slashes. The settings know
 * nothing of the workbench's panes, and the panes know nothing of the project file, so the conversion sits between
 * them in the state package.
 *
 * The stored panes hold no ids. [restore] numbers the panes and splits afresh, and [store] forgets their ids.
 */
internal object StoredPaneLayouts {

    /**
     * The stored form of [layout] for the project folder [root]. A tab of a file outside [root] is left out, and a
     * pane left without tabs is dropped as [StoredPanes.withoutEmptyPanes] describes. A pane whose active tab was left
     * out names no active tab.
     */
    fun store(layout: PaneLayout, root: Path): StoredPanes {
        val active = layout.panes.indexOfFirst { it.id == layout.activePane }.coerceAtLeast(0)
        return StoredPanes(stored(layout.root, root), active).withoutEmptyPanes()
    }

    /**
     * The layout that [stored] describes for the project folder [root]. Every file is resolved against [root], and a
     * file that would lie outside it is left out. A pane without an active tab, or whose active tab was left out,
     * has its last tab active. The panes get the ids from [firstId] upwards in focus order, and the splits the ids
     * after them, so the pane of an editor area that is not split keeps the id [firstId]. A share is kept between
     * `PaneLayout.MIN_SHARE` and `PaneLayout.MAX_SHARE`, as a drag keeps it. Panes may be empty, since
     * the restore that opens the files drops the panes that end up without a tab.
     */
    fun restore(stored: StoredPanes, root: Path, firstId: Int): PaneLayout {
        var paneId = firstId
        var splitId = firstId + stored.panes.size
        fun live(node: StoredPanes.Node): PaneNode = when (node) {
            is StoredPanes.Node.Pane -> {
                val tabs = node.tabs.mapNotNull { resolve(root, it) }.distinct()
                val active = node.active?.let { resolve(root, it) }?.takeIf { it in tabs } ?: tabs.lastOrNull()
                PaneNode.Pane(PaneId(paneId++), tabs, active)
            }
            is StoredPanes.Node.Split -> {
                val first = live(node.first)
                val second = live(node.second)
                val share = node.share.coerceIn(PaneLayout.MIN_SHARE, PaneLayout.MAX_SHARE)
                PaneNode.Split(SplitId(splitId++), orientationOf(node.orientation), share, first, second)
            }
        }
        return PaneLayout(live(stored.root), PaneId(firstId + stored.activePane))
    }

    /** The stored form of the tree [node]. */
    private fun stored(node: PaneNode, root: Path): StoredPanes.Node = when (node) {
        is PaneNode.Pane -> StoredPanes.Node.Pane(
            node.tabs.mapNotNull { relative(root, it) }.distinct(),
            node.active?.let { relative(root, it) },
        )
        is PaneNode.Split -> StoredPanes.Node.Split(
            if (node.orientation == SplitOrientation.ROW) StoredPanes.Orientation.ROW else StoredPanes.Orientation.COLUMN,
            node.share,
            stored(node.first, root),
            stored(node.second, root),
        )
    }

    private fun orientationOf(orientation: StoredPanes.Orientation): SplitOrientation =
        if (orientation == StoredPanes.Orientation.ROW) SplitOrientation.ROW else SplitOrientation.COLUMN

    /**
     * [name] resolved against [root], or null if the result lies outside [root] or [name] is no path on this platform,
     * as a hand-edited project file may hold.
     */
    private fun resolve(root: Path, name: String): Path? =
        try {
            root.resolve(name).normalize().takeIf { it.startsWith(root) && it != root }
        } catch (_: InvalidPathException) {
            null
        }

    /** [path] relative to [root] with forward slashes, or null if it is not below [root]. */
    private fun relative(root: Path, path: Path): String? =
        if (path.startsWith(root) && path != root) root.relativize(path).joinToString("/") { it.toString() } else null
}
