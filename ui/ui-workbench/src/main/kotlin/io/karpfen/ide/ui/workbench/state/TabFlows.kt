package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ConflictResult
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import java.nio.file.Path

/**
 * The flows of open tabs: saving (through [saving], which asks before writing over an external change), closing with
 * the unsaved-changes dialog, and resolving the external-change conflict of the editor area's banner. RELOAD means
 * "take the disk state", which for a file deleted on disk means closing the tab without saving; a failure becomes an
 * error dialog.
 */
internal class TabFlows(
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val unsaved: UnsavedChangesFlow,
    private val saving: SaveFlow,
    private val dialogs: DialogState,
) {

    /** Saves the active tab's document. */
    suspend fun saveActive() {
        val document = tabs.active.value?.let(tabs::documentFor) ?: return
        saving.save(listOf(document))
    }

    /** Saves every dirty document. */
    suspend fun saveAll() {
        saving.save(tabs.documents().filter { it.isDirty.value })
    }

    /** Closes the tab of [path] unless the unsaved-changes dialog is cancelled. */
    suspend fun closeTab(path: Path) {
        val document = tabs.documentFor(path) ?: return
        if (unsaved.confirm(listOf(document))) lifecycle.close(path)
    }

    /** Resolves the conflict of [path]'s document with [resolution]; a file deleted on disk closes its tab. */
    suspend fun resolveConflict(path: Path, resolution: ConflictResolution) {
        val document = tabs.documentFor(path) ?: return
        when (val result = document.resolveConflict(resolution)) {
            ConflictResult.DeletedOnDisk -> lifecycle.close(path)
            is ConflictResult.Failed -> dialogs.showError("Could not reload ${path.fileName}", result.message)
            ConflictResult.Reloaded, ConflictResult.Kept, ConflictResult.NoConflict -> Unit
        }
    }
}
