package io.karpfen.ide.ui.workbench.projecttree.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.foundation.widgets.ContextMenuAction
import io.karpfen.ide.ui.foundation.widgets.TreeView
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeMenu
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path

/**
 * The Project tool window (spec §8): `TreeView` of [tree] with file-kind icons; double-click/Enter opens a file and
 * toggles a directory; context menu from [menu] (New KMeta/KModel/KStates/Environment file, Rename, Delete, Reveal in
 * Finder/Explorer). The callbacks call the non-suspending `WorkbenchState` intents and `ProjectTreeState` intents
 * directly (no coroutine launched here, so a flow survives the row that started it).
 */
@Composable
internal fun ProjectTreeView(
    tree: WorkspaceTree,
    expanded: Set<Path>,
    selected: Path?,
    menu: ProjectTreeMenu,
    onToggle: (Path) -> Unit,
    onSelect: (Path) -> Unit,
    onOpen: (Path) -> Unit,
    onNewFile: (directory: Path, kind: FileTemplateKind) -> Unit,
    onRename: (Path) -> Unit,
    onDelete: (Path) -> Unit,
    onRevealInFileManager: (Path) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(tree) { ProjectTreeNodes(tree) }
    fun run(command: ProjectTreeCommand) = when (command) {
        is ProjectTreeCommand.NewFile -> onNewFile(command.directory, command.kind)
        is ProjectTreeCommand.Rename -> onRename(command.path)
        is ProjectTreeCommand.Delete -> onDelete(command.path)
        is ProjectTreeCommand.RevealInFileManager -> onRevealInFileManager(command.path)
    }
    TreeView(
        roots = nodes.roots,
        expanded = expanded,
        selected = selected,
        onToggleExpanded = onToggle,
        onSelect = onSelect,
        onOpen = { path -> if (nodes.isDirectory(path)) onToggle(path) else onOpen(path) },
        modifier = modifier,
        contextMenu = { node ->
            menu.entries(node.key, nodes.isDirectory(node.key), isRoot = node.key == nodes.root).map { entry ->
                ContextMenuAction(entry.label, entry.isEnabled) { run(entry.command) }
            }
        },
    )
}
