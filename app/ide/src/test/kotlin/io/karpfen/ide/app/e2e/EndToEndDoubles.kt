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
package io.karpfen.ide.app.e2e

import io.karpfen.ide.core.workspace.files.FileChange
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.files.desktop.desktopProjectFileOperations
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [FileWatcher] that reports nothing, because the end-to-end tests change no file behind the workspace's back. Its
 * flow stays open until the workspace cancels it, like the flow of a real watcher. A completed flow would mean that
 * watching ended.
 */
internal class SilentFileWatcher : FileWatcher {
    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> = flow { awaitCancellation() }
}

/**
 * [ProjectFileOperations] on the real file system. It records the platform calls for revealing a file and for opening
 * it externally instead of making them, so no test starts Finder or another application. Creating and renaming use the
 * production adapter, which makes no platform calls itself.
 */
internal class RecordingFileOperations : ProjectFileOperations {
    private val fileSystem = desktopProjectFileOperations()

    /** The paths revealed in the file manager, in call order. */
    val revealed: MutableList<Path> = CopyOnWriteArrayList()

    /** The paths opened in the default application, in call order. */
    val openedExternally: MutableList<Path> = CopyOnWriteArrayList()

    override val supportsTrash: Boolean = false
    override val supportsRevealInFileManager: Boolean = true
    override val supportsOpenExternally: Boolean = true

    override fun createFile(directory: Path, fileName: String, content: String): Path =
        fileSystem.createFile(directory, fileName, content)

    override fun rename(path: Path, newName: String): Path = fileSystem.rename(path, newName)

    override fun delete(path: Path): DeleteOutcome {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        return DeleteOutcome.DELETED
    }

    override fun revealInFileManager(path: Path) {
        revealed.add(path)
    }

    override fun openExternally(path: Path) {
        openedExternally.add(path)
    }
}

/** A [FolderChooser] that is never expected to be asked, because the folder comes from the command line. */
internal class UnusedFolderChooser : FolderChooser {
    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? =
        throw AssertionError("the end-to-end tests open their folder through prepare")
}
