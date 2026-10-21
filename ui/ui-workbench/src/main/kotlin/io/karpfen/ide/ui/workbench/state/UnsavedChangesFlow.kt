package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState

/**
 * The unsaved-changes dialog before closing documents (tab close, folder switch, rename, exit; spec §8): asks about the
 * dirty ones among them (no dialog when none is dirty) and answers whether the caller may go on — Save saves them first
 * through [saving] (a failed save, or a declined overwrite of an external change, stops), Discard goes on without
 * saving, Cancel stops.
 */
internal class UnsavedChangesFlow(private val saving: SaveFlow, private val dialogs: DialogState) {

    /** True if the caller may close [documents]. */
    suspend fun confirm(documents: List<OpenDocument>): Boolean {
        val dirty = documents.filter { it.isDirty.value }
        if (dirty.isEmpty()) return true
        return when (dialogs.ask(DialogRequest.UnsavedChanges(dirty.map { it.path }))) {
            DialogResponse.Save -> saving.save(dirty)
            DialogResponse.Discard -> true
            DialogResponse.Cancel -> false
        }
    }
}
