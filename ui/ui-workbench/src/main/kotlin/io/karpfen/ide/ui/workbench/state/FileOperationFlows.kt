package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The project tree's file operations except rename ([RenameFlow]): new file from a template, delete and reveal in the
 * file manager (spec §8). Blocking file work runs on [ioDispatcher]; failures become error dialogs.
 */
internal class FileOperationFlows(
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val lifecycle: EditorLifecycle,
    private val files: FileOpenFlow,
    private val projectTree: ProjectTreeState,
    private val dialogs: DialogState,
    private val fileOperations: ProjectFileOperations,
    private val templates: FileTemplates,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Asks for the name, creates the file with the template's content for the directory's context, refreshes the
     * workspace, reveals the file in the tree and opens it.
     */
    suspend fun createFile(directory: Path, kind: FileTemplateKind) {
        val workspace = holder.workspace.value ?: return
        val answer = dialogs.ask(DialogRequest.NewFile(directory, kind))
        val name = (answer as? DialogResponse.Text)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val fileName = templates.fileName(kind, name)
        val context = TemplateContextBuilder.build(directory, workspace.contexts.value, workspace.analysis.value)
        val content = templates.content(kind, fileName.dropLast(kind.suffix.length), context)
        val created = try {
            withContext(ioDispatcher) { fileOperations.createFile(directory, fileName, content) }
        } catch (e: IOException) {
            dialogs.showError("Cannot create $fileName", e.reason())
            return
        }
        workspace.refresh()
        projectTree.reveal(created.toAbsolutePath().normalize())
        files.openFile(created.toAbsolutePath().normalize())
    }

    /** Confirms, closes the affected tabs without saving, deletes and refreshes the workspace (not the open folder itself). */
    suspend fun delete(path: Path) {
        val workspace = holder.workspace.value?.takeIf { it.root != path } ?: return
        val answer = dialogs.ask(DialogRequest.ConfirmDelete(path, toTrash = fileOperations.supportsTrash))
        if (answer != DialogResponse.Confirm) return
        tabs.documents().map { it.path }.filter { it.startsWith(path) }.forEach { lifecycle.close(it) }
        try {
            withContext(ioDispatcher) { fileOperations.delete(path) }
        } catch (e: IOException) {
            dialogs.showError("Cannot delete ${path.fileName}", e.reason())
        }
        workspace.refresh()
    }

    /** Shows [path] in Finder/Explorer; a platform failure is logged. */
    suspend fun revealInFileManager(path: Path) {
        try {
            withContext(ioDispatcher) { fileOperations.revealInFileManager(path) }
        } catch (e: Exception) {
            logger.warn("Could not reveal {} in the file manager", path, e)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FileOperationFlows::class.java)
    }
}

/** A user-readable reason of a failed file operation. */
internal fun IOException.reason(): String = when (this) {
    is FileAlreadyExistsException -> "${file ?: message} already exists."
    is NoSuchFileException -> "${file ?: message} does not exist."
    else -> message ?: toString()
}
