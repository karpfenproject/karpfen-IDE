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

import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.SaveResult
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState

/**
 * Saves open documents for Save, Save All and Save in the unsaved-changes dialog. It never silently writes over a
 * change made on disk.
 *
 * A document may have an unresolved conflict with a change on disk. That is `OpenDocument.conflict`, which the banner
 * offers to resolve with Reload or Keep mine. Such a document is only saved after the user confirmed the overwrite
 * through [DialogRequest.ConfirmOverwrite]. One dialog covers all of them. On Cancel they stay unsaved and in
 * conflict, and the first of them becomes the active tab, so its banner shows. The other documents are saved anyway.
 * A failed save leads to an error dialog.
 */
internal class SaveFlow(
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val dialogs: DialogState,
) {

    /** Saves [documents], clean ones included. Returns true if every one of them was saved. */
    suspend fun save(documents: List<OpenDocument>): Boolean {
        val workspace = holder.workspace.value ?: return false
        val inConflict = documents.filter { it.conflict.value != null }
        val overwrite = inConflict.isEmpty() ||
            dialogs.ask(DialogRequest.ConfirmOverwrite(inConflict.map { it.path })) == DialogResponse.Confirm
        val saving = if (overwrite) documents else documents - inConflict.toSet()
        val failures = saving.map { workspace.documents.save(it.path) }.filterIsInstance<SaveResult.Failed>()
        if (!overwrite) tabs.activate(inConflict.first().path)
        if (failures.isNotEmpty()) dialogs.showError(failureTitle(failures), failures.joinToString("\n") { it.message })
        return overwrite && failures.isEmpty()
    }

    /** The title of the error dialog. It names the file when a single save failed. */
    private fun failureTitle(failures: List<SaveResult.Failed>): String =
        failures.singleOrNull()?.let { "Could not save ${it.path.fileName}" } ?: "Could not save"
}
