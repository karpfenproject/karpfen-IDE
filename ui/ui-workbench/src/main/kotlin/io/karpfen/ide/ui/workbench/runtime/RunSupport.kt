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
package io.karpfen.ide.ui.workbench.runtime

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * What a run needs from the workbench around the runtime state: the active editor, the save-all flow, the dialogs and
 * the Runtime tool window. The mediator implements it over its sub-states, so the runtime state never sees them. Used
 * from the UI thread.
 */
internal interface RunSupport {
    /** The file of the active editor tab, or null without one. */
    val activeFile: StateFlow<Path?>

    /**
     * Saves every open file through the workbench's save-all flow, which asks before writing over a change made on disk
     * and shows a failed save in an error dialog; true if every file was saved.
     */
    suspend fun saveAll(): Boolean

    /**
     * Asks the user which of [descriptors] of the workspace at [root] to run, with [preselected] selected at first, and
     * answers the chosen one, or null if the user cancelled.
     */
    suspend fun chooseDescriptor(root: Path, descriptors: List<Path>, preselected: Path?): Path?

    /** Shows an error dialog with [title] and [message] and returns once it is acknowledged. */
    suspend fun showError(title: String, message: String)

    /** Shows the Runtime tool window. */
    fun showRuntimeToolWindow()
}
