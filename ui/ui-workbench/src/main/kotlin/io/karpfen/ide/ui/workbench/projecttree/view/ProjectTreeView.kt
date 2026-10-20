package io.karpfen.ide.ui.workbench.projecttree.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path

/**
 * The Project tool window (spec §8): `TreeView` of [tree] with file-kind icons; double-click/Enter opens; context
 * menu New KMeta/KModel/KStates/Environment file, Rename, Delete, Reveal in Finder/Explorer.
 */
@Composable
internal fun ProjectTreeView(
    tree: WorkspaceTree,
    expanded: Set<Path>,
    selected: Path?,
    onToggle: (Path) -> Unit,
    onSelect: (Path) -> Unit,
    onOpen: (Path) -> Unit,
    onNewFile: (directory: Path, kind: FileTemplateKind) -> Unit,
    onRename: (Path) -> Unit,
    onDelete: (Path) -> Unit,
    onReveal: (Path) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-WORKBENCH: project tree view")
