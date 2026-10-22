package io.karpfen.ide.ui.workbench.projecttree.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.widgets.ContextMenuAction
import io.karpfen.ide.ui.foundation.widgets.TreeView
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeMenuEntry
import java.nio.file.Path

/**
 * The Project tool window: `TreeView` of [tree] with file-kind icons; double-click/Enter reports [onOpen]
 * (`WorkbenchState.openNode`: toggles a directory, opens a file); the context menu shows [menuFor] (New
 * KMeta/KModel/KStates/Environment file, Rename, Delete, Reveal in Finder/Explorer, decided by `ProjectTreeState`) and
 * hands the chosen command to [onCommand]. Renders and forwards only: the callbacks are the non-suspending
 * `WorkbenchState` intents and `ProjectTreeState` intents (no coroutine launched here, so a flow survives the row that
 * started it).
 */
@Composable
internal fun ProjectTreeView(
    tree: WorkspaceTree,
    expanded: Set<Path>,
    selected: Path?,
    menuFor: (Path) -> List<ProjectTreeMenuEntry>,
    onToggle: (Path) -> Unit,
    onSelect: (Path) -> Unit,
    onOpen: (Path) -> Unit,
    onCommand: (ProjectTreeCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(tree) { ProjectTreeNodes(tree) }
    TreeView(
        roots = nodes.roots,
        expanded = expanded,
        selected = selected,
        onToggleExpanded = onToggle,
        onSelect = onSelect,
        onOpen = onOpen,
        modifier = modifier,
        contextMenu = { node ->
            menuFor(node.key).map { entry -> ContextMenuAction(entry.label, entry.isEnabled) { onCommand(entry.command) } }
        },
    )
}
