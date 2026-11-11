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

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/**
 * Renames a file or a directory from the project tree. The flow asks for the new name. It then runs the unsaved-changes
 * dialog over the affected open documents. For a file that is the file itself. For a directory it is every open
 * document below it. Cancel aborts the rename.
 *
 * The flow then closes those documents, renames on [ioDispatcher] and refreshes the workspace. It reopens each document
 * under its new path at its former tab position, and the active tab stays active. When the rename fails, an error
 * dialog says why and the documents are reopened under their old paths. The opened folder itself is never renamed
 * here.
 */
internal class RenameFlow(
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val unsaved: UnsavedChangesFlow,
    private val projectTree: ProjectTreeState,
    private val dialogs: DialogState,
    private val fileOperations: ProjectFileOperations,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Renames [path] as the class describes. Nothing happens for the opened folder itself, for a blank name or for the
     * unchanged name.
     */
    suspend fun rename(path: Path) {
        val workspace = holder.workspace.value?.takeIf { it.root != path } ?: return
        val answer = dialogs.ask(DialogRequest.Rename(path))
        val newName = (answer as? DialogResponse.Text)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (newName == path.fileName?.toString()) return
        val affected = tabs.documents().filter { it.path.startsWith(path) }
        if (!unsaved.confirm(affected)) return

        val positions = affected.map { it.path }.associateWith(tabs::indexOf)
        val active = tabs.active.value
        positions.keys.forEach { lifecycle.close(it) }
        val renamed = try {
            withContext(ioDispatcher) { fileOperations.rename(path, newName) }.toAbsolutePath().normalize()
        } catch (e: IOException) {
            dialogs.showError("Cannot rename ${path.fileName}", e.reason())
            null
        }
        workspace.refresh()

        val moved = { old: Path -> if (renamed == null) old else renamed.resolve(path.relativize(old)).normalize() }
        positions.entries.sortedBy { it.value }.forEach { (old, index) -> lifecycle.open(moved(old), index) }
        active?.let { tabs.activate(if (it in positions) moved(it) else it) }
        renamed?.let(projectTree::reveal)
    }
}
