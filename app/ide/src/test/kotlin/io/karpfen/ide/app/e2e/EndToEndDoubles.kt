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
 * A [FileWatcher] that reports nothing (the end-to-end tests change no file behind the workspace's back). Its flow
 * stays open until the workspace cancels it, like a real watcher: a completed flow would mean "watching ended".
 */
internal class SilentFileWatcher : FileWatcher {
    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> = flow { awaitCancellation() }
}

/**
 * [ProjectFileOperations] on the real file system that records the platform calls (reveal, open externally) instead
 * of making them, so no test starts Finder or another application. Creating and renaming are the production adapter's
 * (headless, so it makes no platform calls itself).
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

/** A [FolderChooser] that is never expected to be asked (the folder comes from the command line). */
internal class UnusedFolderChooser : FolderChooser {
    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? =
        throw AssertionError("the end-to-end tests open their folder through prepare")
}
