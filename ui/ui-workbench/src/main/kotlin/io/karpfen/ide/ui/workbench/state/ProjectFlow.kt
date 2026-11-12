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

import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Moves the workbench from the settings of one project to those of another. A project keeps its theme, its zoom, the
 * layout of its tool windows, its runtime choice and its open files in the file `.karpfenproject` in its folder. The
 * folder flow calls the three steps in this order:
 *
 * 1. [leave] before the open folder is closed. It writes the layout and the open files of that folder one last time
 *    and then keeps the tabs that close out of the project file.
 * 2. [enter] once the new folder can be opened. It makes the settings repository read the new project file and gives
 *    the layout what that file holds. From here on the theme, the zoom and the tool windows are those of the new
 *    project. With null it goes back to the defaults of no project.
 * 3. [restoreTabs] after the workspace is open. It opens the files the project file lists, activates the one that was
 *    active, and lets the open files be written again.
 */
internal class ProjectFlow(
    private val settings: SettingsRepository,
    private val layout: LayoutState,
    private val layoutPersister: LayoutPersister,
    private val openFiles: OpenFilesPersister,
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Writes the state of the open project and stops recording the tabs. */
    suspend fun leave() {
        layoutPersister.persistNow()
        openFiles.persistNow()
        openFiles.pause()
    }

    /**
     * Makes [root] the project whose settings apply, or none for null, and restores the layout from them. Without a
     * project the tabs are recorded again at once, because there are none to restore.
     */
    suspend fun enter(root: Path?) {
        layout.restore(settings.openProject(root))
        if (root == null) openFiles.resume()
    }

    /**
     * Opens the files that the project file of [root] lists, in their order, and activates the one that was active.
     * A file that is gone or cannot be opened in an editor is skipped without a word, since the list may be older
     * than the folder's content.
     */
    suspend fun restoreTabs(root: Path) {
        val stored = settings.settings.value
        try {
            for (name in stored.openFiles) {
                val path = root.resolve(name).normalize()
                if (path.startsWith(root) && withContext(ioDispatcher) { Files.isRegularFile(path) }) lifecycle.open(path)
            }
            stored.activeFile
                ?.let { root.resolve(it).normalize() }
                ?.takeIf { tabs.indexOf(it) >= 0 }
                ?.let(tabs::activate)
        } finally {
            openFiles.resume()
        }
    }
}
