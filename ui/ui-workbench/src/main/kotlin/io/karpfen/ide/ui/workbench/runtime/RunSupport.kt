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
 * What a run needs from the workbench around the runtime state. That is the active editor, the save-all flow, the
 * dialogs and the Runtime tool window. The mediator implements it over its sub-states, so the runtime state never sees
 * them. Use it from the UI thread only.
 */
internal interface RunSupport {
    /** The file of the active editor tab, or null if there is none. */
    val activeFile: StateFlow<Path?>

    /**
     * Saves every open file through the workbench's save-all flow. That flow asks before it writes over a change made
     * on disk, and it shows a failed save in an error dialog. Returns true if every file was saved.
     */
    suspend fun saveAll(): Boolean

    /**
     * Asks the user which of the [descriptors] of the workspace at [root] to run. [preselected] is selected when the
     * dialog opens. Returns the chosen descriptor, or null if the user cancelled.
     */
    suspend fun chooseDescriptor(root: Path, descriptors: List<Path>, preselected: Path?): Path?

    /**
     * Asks the user how to run: which of the [bridges] of the workspace at [root] to start with the run, and whether to
     * capture the run to a file. The dialog opens with the boxes of [preselected] ticked and the capture box ticked if
     * [capture] is true. Returns the choice, or null if the user cancelled.
     */
    suspend fun configureRun(root: Path, bridges: List<Path>, preselected: Set<Path>, capture: Boolean): RunChoice?

    /** Shows an error dialog with [title] and [message] and returns once it is acknowledged. */
    suspend fun showError(title: String, message: String)

    /** Shows the Runtime tool window. */
    fun showRuntimeToolWindow()
}
