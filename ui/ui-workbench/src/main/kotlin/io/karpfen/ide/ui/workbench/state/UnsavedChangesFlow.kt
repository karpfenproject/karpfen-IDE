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
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState

/**
 * The unsaved-changes dialog before closing documents (tab close, folder switch, rename, exit): asks about the dirty
 * ones among them (no dialog when none is dirty) and answers whether the caller may go on — Save saves them first
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
