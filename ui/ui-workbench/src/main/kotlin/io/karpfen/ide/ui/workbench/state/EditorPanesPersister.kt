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

import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Writes the editor panes into the settings of the project: the tree of panes with the share of every split, the files
 * open in the tabs of every pane, the active tab of every pane and the active pane, as `StoredPaneLayouts.store` turns
 * them into [io.karpfen.ide.ui.workbench.settings.StoredPanes]. The files are named relative to the project folder
 * with forward slashes, so the project file means the same on every machine. A tab of a file outside the project folder
 * is left out.
 *
 * Writes are debounced by `LayoutPersister.DEBOUNCE` after the last change of the panes, so a drag of the border
 * between two panes writes once when it rests and not for every step. While the workbench moves from one folder to
 * another, the tabs of the old folder close and those of the new one open. [pause] keeps these passing states out of
 * both project files, and [resume] ends that. [persistNow] writes at once, before a folder is left and on exit.
 */
internal class EditorPanesPersister(
    private val scope: CoroutineScope,
    private val tabs: EditorTabsState,
    private val holder: WorkspaceHolder,
    private val settings: SettingsRepository,
) {
    private var job: Job? = null
    private var paused = false

    /** Starts writing changes of the panes, debounced. */
    @OptIn(FlowPreview::class)
    fun start() {
        job?.cancel()
        job = scope.launch {
            tabs.layout.debounce(LayoutPersister.DEBOUNCE).collect { persistNow() }
        }
    }

    /** Stops writing until [resume]. */
    fun pause() {
        paused = true
    }

    /** Writes again after [pause]. */
    fun resume() {
        paused = false
    }

    /** Writes the panes now, unless writing is paused or no folder is open. */
    suspend fun persistNow() {
        if (paused) return
        val root = holder.workspace.value?.root ?: return
        val panes = StoredPaneLayouts.store(tabs.layout.value, root)
        if (settings.settings.value.editorPanes != panes) settings.update { it.copy(editorPanes = panes) }
    }
}
