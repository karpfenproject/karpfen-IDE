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
 * The file operations of the project tree, except rename, which [RenameFlow] handles. They are a new file from a
 * template, delete, and show in the file manager. Blocking file work runs on [ioDispatcher]. Failures become error
 * dialogs.
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
     * Asks for the name and creates the file with the template's content for the directory's context. Then it
     * refreshes the workspace, reveals the file in the tree and opens it.
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

    /**
     * Asks for confirmation, closes the affected tabs without saving, deletes [path] and refreshes the workspace. The
     * open folder itself cannot be deleted. The workspace is refreshed even when the deletion fails.
     */
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

    /** Shows [path] in Finder or Explorer. A platform failure is only logged. */
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

/** A reason for a failed file operation that a user can read. */
internal fun IOException.reason(): String = when (this) {
    is FileAlreadyExistsException -> "${file ?: message} already exists."
    is NoSuchFileException -> "${file ?: message} does not exist."
    else -> message ?: toString()
}
