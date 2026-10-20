package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.document.OpenResult
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

/**
 * Opening a file (spec §8, decision 14 of the work-package plan): karpfen sources open in an editor tab (or activate
 * theirs); files the editor cannot open (`OpenResult.Unsupported`, e.g. `*.kenv.json`) open in the system's default
 * application through [fileOperations] on [ioDispatcher]; failures become an error dialog.
 */
internal class FileOpenFlow(
    private val lifecycle: EditorLifecycle,
    private val dialogs: DialogState,
    private val fileOperations: ProjectFileOperations,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Opens [path]; true if it is now the active editor tab. */
    suspend fun openFile(path: Path): Boolean = when (val result = lifecycle.open(path)) {
        is OpenResult.Opened -> true
        is OpenResult.Unsupported -> {
            openExternally(path)
            false
        }
        is OpenResult.Failed -> {
            dialogs.showError("Cannot open ${path.fileName}", result.message)
            false
        }
        null -> {
            dialogs.showError("Cannot open ${path.fileName}", "Open a folder first.")
            false
        }
    }

    private suspend fun openExternally(path: Path) {
        if (!fileOperations.supportsOpenExternally) {
            logger.info("Opening {} in the default application is not supported on this platform", path)
        }
        try {
            withContext(ioDispatcher) { fileOperations.openExternally(path) }
        } catch (e: IOException) {
            logger.warn("Could not open {} in the default application", path, e)
            dialogs.showError("Cannot open ${path.fileName}", e.message ?: e.toString())
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FileOpenFlow::class.java)
    }
}
