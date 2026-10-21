package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ConflictResult
import io.karpfen.ide.core.workspace.document.SaveResult
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import java.nio.file.Path

/**
 * The flows of open tabs (spec §4, §8): saving, closing with the unsaved-changes dialog, and resolving the
 * external-change conflict of the editor area's banner (decision 43 of the work-package plan: RELOAD of a file deleted
 * on disk closes the tab without saving, a failure becomes an error dialog).
 */
internal class TabFlows(
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val unsaved: UnsavedChangesFlow,
    private val dialogs: DialogState,
) {

    suspend fun saveActive() {
        val path = tabs.active.value ?: return
        val workspace = holder.workspace.value ?: return
        val result = workspace.documents.save(path)
        if (result is SaveResult.Failed) dialogs.showError("Could not save ${path.fileName}", result.message)
    }

    suspend fun saveAll() {
        val workspace = holder.workspace.value ?: return
        val failures = workspace.documents.saveAll().filterIsInstance<SaveResult.Failed>()
        if (failures.isNotEmpty()) dialogs.showError("Could not save", failures.joinToString("\n") { it.message })
    }

    suspend fun closeTab(path: Path) {
        val document = tabs.documentFor(path) ?: return
        if (unsaved.confirm(listOf(document))) lifecycle.close(path)
    }

    suspend fun resolveConflict(path: Path, resolution: ConflictResolution) {
        val document = tabs.documentFor(path) ?: return
        when (val result = document.resolveConflict(resolution)) {
            ConflictResult.DeletedOnDisk -> lifecycle.close(path)
            is ConflictResult.Failed -> dialogs.showError("Could not reload ${path.fileName}", result.message)
            ConflictResult.Reloaded, ConflictResult.Kept, ConflictResult.NoConflict -> Unit
        }
    }
}
