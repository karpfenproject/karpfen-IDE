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

import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Registers the workbench's own commands from `WorkbenchCommandIds`. They are Open Folder…, one Open Recent command
 * per recent folder, Save, Save All, Close Tab, one toggle per `ToolWindowId` and the theme switches. The Open Recent
 * commands are kept up to date with the settings. Each command calls a non-suspending intent of [intents] or an intent
 * of [layout].
 */
internal class WorkbenchCommands(
    private val scope: CoroutineScope,
    private val intents: WorkbenchState,
    private val tabs: EditorTabsState,
    private val layout: LayoutState,
    private val problems: ProblemsState,
    private val settings: SettingsRepository,
) {

    /**
     * Registers the commands in [registry]. Save and Close Tab need an active tab. Save All needs a dirty tab. The
     * toggle of the Problems tool window needs a problem to show, because the tool window stays closed without one.
     */
    fun register(registry: CommandRegistry) {
        val hasActiveTab = tabs.active.map { it != null }.eagerly(tabs.active.value != null)
        val hasDirtyTab = tabs.tabs.map { list -> list.any { it.isDirty } }.eagerly(tabs.tabs.value.any { it.isDirty })
        registry.register(Command(WorkbenchCommandIds.OPEN_FOLDER, "Open Folder…", primary(KeyCode.O)) { intents.chooseAndOpenFolder() })
        registry.register(Command(WorkbenchCommandIds.SAVE, "Save", primary(KeyCode.S), hasActiveTab) { intents.saveActive() })
        registry.register(
            Command(WorkbenchCommandIds.SAVE_ALL, "Save All", KeyShortcut(KeyCode.S, primary = true, alt = true), hasDirtyTab) {
                intents.saveAll()
            },
        )
        registry.register(
            Command(WorkbenchCommandIds.CLOSE_TAB, "Close Tab", primary(KeyCode.W), hasActiveTab) { intents.closeActiveTab() },
        )
        val hasProblems = problems.state.map { !it.isEmpty }.eagerly(!problems.state.value.isEmpty)
        ToolWindowId.entries.forEach { id ->
            val available = if (id == ToolWindowId.PROBLEMS) hasProblems else Command.ALWAYS_ENABLED
            registry.register(
                Command(WorkbenchCommandIds.toggleToolWindow(id), id.title, isEnabled = available) { layout.toggleToolWindow(id) },
            )
        }
        registry.register(Command(WorkbenchCommandIds.THEME_LIGHT, "Light Theme") { layout.setTheme(ThemeMode.LIGHT) })
        registry.register(Command(WorkbenchCommandIds.THEME_DARK, "Dark Theme") { layout.setTheme(ThemeMode.DARK) })
        registerRecentFolders(registry)
    }

    /**
     * Registers one Open Recent command per recent folder and keeps the commands in step with the settings. The folder
     * at index n is the command `OPEN_RECENT_PREFIX + n`. The ids of folders that dropped out are unregistered.
     */
    private fun registerRecentFolders(registry: CommandRegistry) {
        var registered = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settings.settings.map { it.recentFolders }.distinctUntilChanged().collect { folders ->
                folders.forEachIndexed { index, folder ->
                    val id = WorkbenchCommandIds.OPEN_RECENT_PREFIX + index
                    registry.register(Command(id, folder) { intents.openFolder(Path.of(folder)) })
                }
                (folders.size until registered).forEach { registry.unregister(WorkbenchCommandIds.OPEN_RECENT_PREFIX + it) }
                registered = folders.size
            }
        }
    }

    /** [key] with the platform's primary modifier, which is Cmd on macOS and Ctrl elsewhere. */
    private fun primary(key: KeyCode): KeyShortcut = KeyShortcut(key, primary = true)

    /** This flow as a state flow in [scope], starting with [initial]. */
    private fun <T> Flow<T>.eagerly(initial: T): StateFlow<T> = stateIn(scope, SharingStarted.Eagerly, initial)
}
