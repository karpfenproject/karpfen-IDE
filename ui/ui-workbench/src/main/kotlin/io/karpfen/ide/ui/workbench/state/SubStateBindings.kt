package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Binds the read-only sub-states to what they follow (spec §8): the project tree to `Workspace.files`, Problems and the
 * status-bar counts to the workspace ([bindWorkspace], called by the folder flow), the Outline and the status bar's
 * caret part to the active tab's editor (followed from [EditorTabsState.activeEditor] once [start]ed, since views
 * switch tabs directly). Collected in [scope].
 */
internal class SubStateBindings(
    private val scope: CoroutineScope,
    private val tabs: EditorTabsState,
    private val projectTree: ProjectTreeState,
    private val outline: OutlineState,
    private val problems: ProblemsState,
    private val statusBar: StatusBarState,
) {
    private var treeBinding: Job? = null

    /** Follows the active editor from now on. */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tabs.activeEditor.collect { editor ->
                val document = tabs.active.value?.let(tabs::documentFor)
                outline.bindEditor(editor)
                statusBar.bindEditor(document, editor)
            }
        }
    }

    /** Binds the tree, Problems and the status-bar counts to [workspace] (null clears them). */
    fun bindWorkspace(workspace: Workspace?) {
        treeBinding?.cancel()
        projectTree.setTree(workspace?.files?.value)
        problems.bindWorkspace(workspace)
        statusBar.bindWorkspace(workspace)
        treeBinding = workspace?.let { bound -> scope.launch(start = CoroutineStart.UNDISPATCHED) { bound.files.collect(projectTree::setTree) } }
    }
}
