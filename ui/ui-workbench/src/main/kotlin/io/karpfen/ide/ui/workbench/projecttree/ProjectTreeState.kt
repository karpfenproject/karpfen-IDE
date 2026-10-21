package io.karpfen.ide.ui.workbench.projecttree

import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * State of the Project tool window (spec §8): the workspace tree, expanded directories and the selection, and what the
 * tree's nodes are ([isDirectory]) and offer ([menuFor]). Opening a node and the commands of the context menu are
 * intents of the `WorkbenchState` (`openNode` — which toggles a directory here and opens a file —, `runTreeCommand`);
 * this class holds tree state and decides from it, so the view only renders and forwards.
 *
 * Public: the read flows, the queries and the pure UI intents ([toggle], [select], [reveal]). The mediator's
 * coordination operation ([setTree]) is internal, so views cannot bypass the mediator (decision 31 of the work-package
 * plan).
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
        // minusElement/plusElement: a Path is an Iterable<Path> of its names, so `set - path` would remove the names.
        expandedPaths.value = expandedPaths.value.let { if (path in it) it.minusElement(path) else it.plusElement(path) }
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
}
