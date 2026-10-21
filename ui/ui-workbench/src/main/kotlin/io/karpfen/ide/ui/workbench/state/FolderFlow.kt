package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opening and closing folders (spec §8): the unsaved-changes flow over every open document, closing the tabs and the
 * old workspace, opening the new one through the [WorkspaceFactory] in [scope] (its dispatcher is the documents'
 * single writer), binding the sub-states and recording the folder as the most recent one. The tree is scanned before
 * the flow ends, so the project tree is filled when the intent's job completes.
 *
 * A folder that is no longer a directory (a stale recent folder: deleted, moved, its volume unmounted) is checked on
 * [ioDispatcher] before anything is closed: the open workspace and its tabs stay, an error dialog says why, and the
 * folder leaves the recent folders.
 */
internal class FolderFlow(
    private val scope: CoroutineScope,
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val unsaved: UnsavedChangesFlow,
    private val bindings: SubStateBindings,
    private val workspaces: WorkspaceFactory,
    private val languageServices: LanguageServicesFactory,
    private val settings: SettingsRepository,
    private val folderChooser: FolderChooser,
    private val dialogs: DialogState,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun openFolder(path: Path) {
        val root = path.toAbsolutePath().normalize()
        if (holder.workspace.value?.root != root) {
            if (!isFolder(root)) return cannotOpen(root, notAFolder(root))
            if (!unsaved.confirm(tabs.documents())) return
            closeWorkspace()
            val workspace = try {
                workspaces.open(root, scope)
            } catch (e: IllegalArgumentException) {
                return cannotOpen(root, e.message ?: notAFolder(root)) // vanished since the check
            }
            holder.set(workspace, languageServices.create(workspace))
            bindings.bindWorkspace(workspace)
            workspace.refresh()
        }
        settings.update { it.withRecentFolder(root.toString()) }
    }

    private suspend fun isFolder(root: Path): Boolean = withContext(ioDispatcher) { Files.isDirectory(root) }

    private fun notAFolder(root: Path): String = "$root does not exist or is not a folder."

    private suspend fun cannotOpen(root: Path, reason: String) {
        settings.update { it.withoutRecentFolder(root.toString()) }
        dialogs.showError("Cannot open folder", reason)
    }

    suspend fun chooseAndOpenFolder() {
        val initial = holder.workspace.value?.root ?: settings.settings.value.recentFolders.firstOrNull()?.let(Path::of)
        val folder = folderChooser.chooseFolder("Open Folder", initial) ?: return
        openFolder(folder)
    }

    /** Closes every tab and the current workspace without asking (unsaved changes are discarded). */
    fun closeWorkspace() {
        lifecycle.closeAllTabs()
        bindings.bindWorkspace(null)
        holder.clear()?.close()
    }
}
