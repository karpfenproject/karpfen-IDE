package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.SaveResult
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState

/**
 * The unsaved-changes dialog before closing documents (tab close, folder switch, rename, exit; spec §8): asks about the
 * dirty ones among them (no dialog when none is dirty) and answers whether the caller may go on — Save saves them first
 * (a failed save shows an error and stops), Discard goes on without saving, Cancel stops.
 */
internal class UnsavedChangesFlow(private val holder: WorkspaceHolder, private val dialogs: DialogState) {

    /** True if the caller may close [documents]. */
    suspend fun confirm(documents: List<OpenDocument>): Boolean {
        val dirty = documents.filter { it.isDirty.value }
        if (dirty.isEmpty()) return true
        return when (dialogs.ask(DialogRequest.UnsavedChanges(dirty.map { it.path }))) {
            DialogResponse.Save -> save(dirty)
            DialogResponse.Discard -> true
            DialogResponse.Cancel -> false
        }
    }

    private suspend fun save(documents: List<OpenDocument>): Boolean {
        val workspace = holder.workspace.value ?: return false
        val failures = documents.map { workspace.documents.save(it.path) }.filterIsInstance<SaveResult.Failed>()
        if (failures.isEmpty()) return true
        dialogs.showError("Could not save", failures.joinToString("\n") { it.message })
        return false
    }
}
