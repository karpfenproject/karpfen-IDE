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

import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ConflictResult
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.tabs.PaneId
import java.nio.file.Path

/**
 * The flows of open tabs. They save through [saving], which asks before it writes over a change made on disk. They
 * close tabs, after the unsaved-changes dialog when the last view of a file closes. And they resolve the conflict with
 * a change on disk that the editor area's banner shows. Reload means "take the state on disk". For a file deleted on
 * disk, that means closing its tabs without saving. A failure leads to an error dialog.
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

    /**
     * Closes the tab of [path] in [pane]. While another pane shows the file, the tab closes without a question,
     * because the document stays open there. The tab of the last view closes unless the unsaved-changes dialog is
     * cancelled.
     */
    suspend fun closeTab(pane: PaneId, path: Path) {
        val document = tabs.documentFor(path) ?: return
        if (tabs.editorFor(pane, path) == null) return
        val lastView = tabs.layout.value.panesShowing(path).size == 1
        if (!lastView || unsaved.confirm(listOf(document))) lifecycle.close(pane, path)
    }

    /**
     * Resolves the conflict of the document at [path] with [resolution]. When the file was deleted on disk, its tabs
     * close in every pane.
     */
    suspend fun resolveConflict(path: Path, resolution: ConflictResolution) {
        val document = tabs.documentFor(path) ?: return
        when (val result = document.resolveConflict(resolution)) {
            ConflictResult.DeletedOnDisk -> lifecycle.closeEverywhere(path)
            is ConflictResult.Failed -> dialogs.showError("Could not reload ${path.fileName}", result.message)
            ConflictResult.Reloaded, ConflictResult.Kept, ConflictResult.NoConflict -> Unit
        }
    }
}
