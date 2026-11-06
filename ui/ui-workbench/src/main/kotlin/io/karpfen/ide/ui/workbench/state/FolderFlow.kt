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

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opening and closing folders: the unsaved-changes flow over every open document, shutting down the old workspace's
 * runtime, closing the tabs and the old workspace, opening the new one through the [WorkspaceFactory] in [scope] (its
 * dispatcher is the documents' single writer) with its runtime state, binding the sub-states and recording the folder
 * as the most recent one. The tree is scanned before the flow ends, so the project tree is filled when the intent's
 * job completes.
 *
 * A folder that is no longer a directory (a stale recent folder: deleted, moved, its volume unmounted) or that cannot
 * be listed (permissions, a protected folder) is checked on [ioDispatcher] before anything is closed: the open
 * workspace and its tabs stay, an error dialog says why, and the folder leaves the recent folders. A folder dialog
 * that fails is an error dialog too.
 */
internal class FolderFlow(
    private val scope: CoroutineScope,
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val unsaved: UnsavedChangesFlow,
    private val bindings: SubStateBindings,
    private val runtimes: RuntimeHolder,
    private val workspaces: WorkspaceFactory,
    private val languageServices: LanguageServicesFactory,
    private val settings: SettingsRepository,
    private val folderChooser: FolderChooser,
    private val dialogs: DialogState,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Opens [path] as the workspace unless it is open already, and records it as the most recent folder. A folder that
     * passes the check but vanishes before the [WorkspaceFactory] opens it (which rejects it with an
     * `IllegalArgumentException`) cannot be opened either.
     */
    suspend fun openFolder(path: Path) {
        val root = path.toAbsolutePath().normalize()
        if (holder.workspace.value?.root != root) {
            problemOf(root)?.let { return cannotOpen(root, it) }
            if (!unsaved.confirm(tabs.documents())) return
            runtimes.shutdown()
            closeWorkspace()
            val workspace = try {
                workspaces.open(root, scope)
            } catch (e: IllegalArgumentException) {
                return cannotOpen(root, e.message ?: notAFolder(root))
            }
            holder.set(workspace, languageServices.create(workspace))
            bindings.bindWorkspace(workspace)
            runtimes.open(workspace)
            workspace.refresh()
        }
        settings.update { it.withRecentFolder(root.toString()) }
    }

    /** Why [root] cannot be opened, or null if it is a folder that can be listed. */
    private suspend fun problemOf(root: Path): String? = withContext(ioDispatcher) {
        when {
            !Files.isDirectory(root) -> notAFolder(root)
            !canList(root) -> "$root cannot be read; check its permissions."
            else -> null
        }
    }

    /** True if the entries of [root] can be listed. */
    private fun canList(root: Path): Boolean =
        try {
            Files.newDirectoryStream(root).close()
            true
        } catch (e: IOException) {
            false
        }

    /** The reason shown for a [root] that is not an existing directory. */
    private fun notAFolder(root: Path): String = "$root does not exist or is not a folder."

    /** Drops [root] from the recent folders and shows [reason] in an error dialog. */
    private suspend fun cannotOpen(root: Path, reason: String) {
        settings.update { it.withoutRecentFolder(root.toString()) }
        dialogs.showError("Cannot open folder", reason)
    }

    /** Asks the [folderChooser] and opens the chosen folder; a failing dialog becomes an error dialog. */
    suspend fun chooseAndOpenFolder() {
        val initial = holder.workspace.value?.root ?: settings.settings.value.recentFolders.firstOrNull()?.let(Path::of)
        val folder = try {
            folderChooser.chooseFolder("Open Folder", initial)
        } catch (e: IOException) {
            logger.warn("The folder dialog failed", e)
            return dialogs.showError("Cannot choose a folder", e.message ?: e.toString())
        } ?: return
        openFolder(folder)
    }

    /**
     * Closes the runtime state at once, every tab and the current workspace without asking (unsaved changes are
     * discarded).
     */
    fun closeWorkspace() {
        runtimes.close()
        lifecycle.closeAllTabs()
        bindings.bindWorkspace(null)
        holder.clear()?.close()
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FolderFlow::class.java)
    }
}
