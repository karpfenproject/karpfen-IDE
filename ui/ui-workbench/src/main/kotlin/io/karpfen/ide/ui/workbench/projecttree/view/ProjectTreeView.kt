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
 * The Project tool window. It shows a `TreeView` of [tree] with an icon for each file kind. A double-click or Enter
 * reports the node to [onOpen], which is `WorkbenchState.openNode`. That toggles a directory and opens a file. The
 * context menu shows the entries of [menuFor], which `ProjectTreeState` decides. They are the New entries for each
 * file template, Rename, Delete, and Reveal in Finder or Show in Explorer. The chosen command goes to [onCommand].
 *
 * The view only renders and forwards. The callbacks are non-suspending intents of `WorkbenchState` and
 * `ProjectTreeState`. No coroutine is launched here, so a flow keeps running after the row that started it is gone.
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
