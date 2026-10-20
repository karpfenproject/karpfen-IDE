package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * State of the Project tool window (spec §8): the workspace tree, expanded directories and the selection. Opening
 * files and the file operations of the context menu are intents of the [WorkbenchState] (`openFile`, `createFile`,
 * `rename`, `delete`, `reveal`); this class only holds tree state.
 */
public class ProjectTreeState {

    /** The tree of the current workspace, or null without a workspace. */
    public val tree: StateFlow<WorkspaceTree?> get() = TODO("WP-WORKBENCH: tree")

    /** Expanded directories (the root is expanded initially). */
    public val expanded: StateFlow<Set<Path>> get() = TODO("WP-WORKBENCH: expanded directories")

    /** The selected node, or null. */
    public val selected: StateFlow<Path?> get() = TODO("WP-WORKBENCH: selection")

    /** Shows [tree] (null clears); expansion and selection of vanished paths are dropped. */
    public fun setTree(tree: WorkspaceTree?): Unit = TODO("WP-WORKBENCH: set tree")

    /** Expands or collapses the directory [path]. */
    public fun toggle(path: Path): Unit = TODO("WP-WORKBENCH: toggle")

    /** Selects [path]. */
    public fun select(path: Path?): Unit = TODO("WP-WORKBENCH: select")

    /** Expands the ancestors of [path] and selects it (reveal the active file). */
    public fun reveal(path: Path): Unit = TODO("WP-WORKBENCH: reveal")
}
