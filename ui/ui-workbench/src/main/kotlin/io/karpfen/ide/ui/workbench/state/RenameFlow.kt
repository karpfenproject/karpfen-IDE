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
 * Renaming a file or a directory from the project tree (spec §8, decision 42 of the work-package plan): asks for the
 * name, runs the unsaved-changes dialog over the affected open documents (the file, or every open document below the
 * directory; Cancel aborts), closes them, renames on [ioDispatcher], refreshes the workspace and reopens each document
 * under its new path in its former tab position, keeping the active tab active. When the rename fails, an error dialog
 * says why and the documents are reopened under their old paths.
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

    suspend fun rename(path: Path) {
        val workspace = holder.workspace.value ?: return
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
