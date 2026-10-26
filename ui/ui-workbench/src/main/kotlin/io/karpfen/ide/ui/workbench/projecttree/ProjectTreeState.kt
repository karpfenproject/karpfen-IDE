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
 * State of the Project tool window: the workspace tree, expanded directories and the selection, and what the tree's
 * nodes are ([isDirectory]) and offer ([menuFor]). Opening a node and the commands of the context menu are intents of
 * the `WorkbenchState` (`openNode` — which toggles a directory here and opens a file —, `runTreeCommand`); this class
 * holds tree state and decides from it, so the view only renders and forwards.
 *
 * Public: the read flows, the queries and the pure UI intents ([toggle], [select], [reveal]). The mediator's
 * coordination operation ([setTree]) is internal, so views cannot bypass the mediator's coordinated flows.
 *
 * Expansion is remembered across tree updates for the directories that still exist; a new tree root (another folder)
 * starts with only the root expanded. The context [menu] is decided Compose-free as well. Used from the UI thread.
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

    /** Expanded directories (the root is expanded initially). */
    public val expanded: StateFlow<Set<Path>> get() = expandedPaths.asStateFlow()

    /** The selected node, or null. */
    public val selected: StateFlow<Path?> get() = selection.asStateFlow()

    /** True if [path] is a directory of the current tree (opening it toggles it instead of opening a file). */
    public fun isDirectory(path: Path): Boolean = currentTree.value?.find(path) is WorkspaceNode.Directory

    /** The context-menu entries of the node [path] of the current tree ([menu], knowing directories and the root). */
    public fun menuFor(path: Path): List<ProjectTreeMenuEntry> =
        menu.entries(path, isDirectory(path), isRoot = path == currentTree.value?.root?.path)

    /** Shows [tree] (null clears); expansion and selection of vanished paths are dropped. Mediator only. */
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

    /** Expands the ancestors of [path] and selects it (e.g. the active or a newly created file). */
    public fun reveal(path: Path) {
        val root = currentTree.value?.root?.path
        if (root != null && path.startsWith(root)) {
            val ancestors = generateSequence(path.parent) { it.parent }.takeWhile { it.startsWith(root) }
            expandedPaths.value = expandedPaths.value + ancestors
        }
        selection.value = path
    }

    /**
     * [paths] with [path] removed if present, else added. Uses `minusElement`/`plusElement` because a [Path] is an
     * `Iterable<Path>` of its names, so `paths - path` would remove the path's names instead of the path.
     */
    private fun toggled(paths: Set<Path>, path: Path): Set<Path> =
        if (path in paths) paths.minusElement(path) else paths.plusElement(path)
}
