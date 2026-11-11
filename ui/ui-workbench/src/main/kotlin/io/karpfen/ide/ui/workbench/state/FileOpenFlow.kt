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

import io.karpfen.ide.core.workspace.document.OpenResult
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

/**
 * Opens files. Files of a karpfen language open in an editor tab, or activate their tab if it is open already. These
 * are the files of the modeling languages and the environment descriptors. Files that the editor cannot open come back
 * as `OpenResult.Unsupported`. A bridge configuration or a README are examples. They open in the system's default
 * application through [fileOperations] on [ioDispatcher]. That also happens when the Problems view navigates to such a
 * file. Failures become an error dialog.
 */
internal class FileOpenFlow(
    private val lifecycle: EditorLifecycle,
    private val dialogs: DialogState,
    private val fileOperations: ProjectFileOperations,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Opens [path]. Returns true if it is now the active editor tab. */
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

    /**
     * Opens [path] in the system's default application. A failure becomes an error dialog. Where the platform does not
     * support this at all, the attempt is only logged, because the port then does nothing.
     */
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
