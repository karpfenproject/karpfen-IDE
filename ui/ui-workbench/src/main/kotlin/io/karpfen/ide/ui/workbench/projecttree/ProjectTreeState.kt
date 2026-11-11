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
package io.karpfen.ide.ui.workbench.projecttree

import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * The state of the Project tool window. It holds the workspace tree, the expanded directories and the selection. It
 * also answers what a node is through [isDirectory] and what it offers through [menuFor].
 *
 * Opening a node and running a command of the context menu are intents of the `WorkbenchState`. `openNode` toggles a
 * directory here and opens a file. `runTreeCommand` runs a menu command. This class holds the tree state and decides
 * from it, so the view only renders and forwards.
 *
 * The read flows, the queries and the pure UI intents [toggle], [select] and [reveal] are public. The coordination
 * operation [setTree] of the mediator is internal, so views cannot bypass the coordinated flows of the mediator.
 *
 * Expansion is remembered across tree updates for the directories that still exist. A new tree root, which means
 * another folder was opened, starts with only the root expanded. The context [menu] is decided without Compose as
 * well. Use the class from the UI thread only.
 */
public class ProjectTreeState(
    /** The context menu of the tree's nodes. */
    public val menu: ProjectTreeMenu = ProjectTreeMenu(),
) {

    private val currentTree = MutableStateFlow<WorkspaceTree?>(null)
    private val expandedPaths = MutableStateFlow<Set<Path>>(emptySet())
    private val selection = MutableStateFlow<Path?>(null)

    /** The tree of the current workspace, or null without a workspace. */
    public val tree: StateFlow<WorkspaceTree?> get() = currentTree.asStateFlow()

    /** The expanded directories. The root starts expanded. */
    public val expanded: StateFlow<Set<Path>> get() = expandedPaths.asStateFlow()

    /** The selected node, or null if nothing is selected. */
    public val selected: StateFlow<Path?> get() = selection.asStateFlow()

    /** True if [path] is a directory of the current tree. Opening a directory toggles it instead of opening a file. */
    public fun isDirectory(path: Path): Boolean = currentTree.value?.find(path) is WorkspaceNode.Directory

    /**
     * The context menu entries of the node [path] of the current tree. [menu] builds them and is told whether the node
     * is a directory and whether it is the root.
     */
    public fun menuFor(path: Path): List<ProjectTreeMenuEntry> =
        menu.entries(path, isDirectory(path), isRoot = path == currentTree.value?.root?.path)

    /**
     * Shows [tree], or clears the tree for null. Expansion and selection of paths that no longer exist are dropped.
     * Only the mediator calls it.
     */
    internal fun setTree(tree: WorkspaceTree?) {
        val previous = currentTree.value
        currentTree.value = tree
        if (tree == null) {
            expandedPaths.value = emptySet()
            selection.value = null
            return
        }
        expandedPaths.value = if (previous?.root?.path != tree.root.path) {
            setOf(tree.root.path)
        } else {
            expandedPaths.value.filterTo(LinkedHashSet()) { tree.find(it) is WorkspaceNode.Directory }
        }
        selection.value = selection.value?.takeIf { tree.find(it) != null }
    }

    /** Expands or collapses the directory [path]. */
    public fun toggle(path: Path) {
        expandedPaths.value = toggled(expandedPaths.value, path)
    }

    /** Selects [path]. */
    public fun select(path: Path?) {
        selection.value = path
    }

    /**
     * Expands the ancestors of [path] and selects it. Used, for example, for the active file or a newly created one.
     */
    public fun reveal(path: Path) {
        val root = currentTree.value?.root?.path
        if (root != null && path.startsWith(root)) {
            val ancestors = generateSequence(path.parent) { it.parent }.takeWhile { it.startsWith(root) }
            expandedPaths.value = expandedPaths.value + ancestors
        }
        selection.value = path
    }

    /**
     * Returns [paths] with [path] removed if it is present, or added if it is not. This uses `minusElement` and
     * `plusElement`, because a [Path] is an `Iterable<Path>` of its names. `paths - path` would remove the path's names
     * instead of the path.
     */
    private fun toggled(paths: Set<Path>, path: Path): Set<Path> =
        if (path in paths) paths.minusElement(path) else paths.plusElement(path)
}
