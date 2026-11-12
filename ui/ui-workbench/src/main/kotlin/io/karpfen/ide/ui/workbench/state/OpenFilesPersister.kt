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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Writes which files are open in tabs, and which tab is active, into the settings of the project. The files are named
 * relative to the project folder with forward slashes, so the project file means the same on every machine. A tab of
 * a file outside the project folder is left out.
 *
 * Writes are debounced by `LayoutPersister.DEBOUNCE` after the last change of the tabs. While the workbench moves from
 * one folder to another, the tabs of the old folder close and those of the new one open. [pause] keeps these passing
 * states out of both project files, and [resume] ends that. [persistNow] writes at once, before a folder is left and
 * on exit.
 */
internal class OpenFilesPersister(
    private val scope: CoroutineScope,
    private val tabs: EditorTabsState,
    private val holder: WorkspaceHolder,
    private val settings: SettingsRepository,
) {
    private var job: Job? = null
    private var paused = false

    /** Starts writing changes of the tabs, debounced. */
    @OptIn(FlowPreview::class)
    fun start() {
        job?.cancel()
        job = scope.launch {
            combine(tabs.tabs, tabs.active) { _, _ -> }.debounce(LayoutPersister.DEBOUNCE).collect { persistNow() }
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

    /** Writes the open files now, unless writing is paused or no folder is open. */
    suspend fun persistNow() {
        if (paused) return
        val root = holder.workspace.value?.root ?: return
        val open = tabs.tabs.value.mapNotNull { relative(root, it.path) }
        val active = tabs.active.value?.let { relative(root, it) }
        val stored = settings.settings.value
        if (stored.openFiles != open || stored.activeFile != active) {
            settings.update { it.copy(openFiles = open, activeFile = active) }
        }
    }

    /** [path] relative to [root] with forward slashes, or null if it is not below [root]. */
    private fun relative(root: Path, path: Path): String? =
        if (path.startsWith(root) && path != root) root.relativize(path).joinToString("/") { it.toString() } else null
}
