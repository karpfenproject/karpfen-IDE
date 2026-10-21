package io.karpfen.ide.ui.workbench.testing

import io.karpfen.ide.core.workspace.document.DecodedText
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.files.FileChange
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.core.workspace.files.FileWatcher
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.files.desktop.DesktopAction
import io.karpfen.ide.ui.workbench.files.desktop.DesktopActions
import io.karpfen.ide.ui.workbench.files.desktop.DesktopProjectFileOperations
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** A [FileWatcher] that emits the changes a test reports (the real watch service polls slowly on macOS). */
class FakeFileWatcher : FileWatcher {
    private val changes = MutableSharedFlow<FileChange>(extraBufferCapacity = 1024)

    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> = changes.filterNot { isIgnored(it.path) }

    /** Reports a change of [path]. */
    fun report(path: Path, kind: FileChangeKind = FileChangeKind.MODIFIED) {
        check(changes.tryEmit(FileChange(path.toAbsolutePath().normalize(), kind)))
    }
}

/**
 * [ProjectFileOperations] on the real file system that records the platform calls (trash, reveal, open externally)
 * instead of making them; [failNext] makes the next create, rename or delete throw. Creating and renaming are the
 * production adapter's (`DesktopProjectFileOperations`, e.g. its case-only renames), without platform actions.
 */
class RecordingFileOperations(
    override val supportsTrash: Boolean = false,
    override val supportsRevealInFileManager: Boolean = true,
    override val supportsOpenExternally: Boolean = true,
) : ProjectFileOperations {
    val revealed: MutableList<Path> = CopyOnWriteArrayList()
    val openedExternally: MutableList<Path> = CopyOnWriteArrayList()
    val trashed: MutableList<Path> = CopyOnWriteArrayList()
    var failNext: IOException? = null

    private val fileSystem: ProjectFileOperations = DesktopProjectFileOperations(NoDesktopActions)

    override fun createFile(directory: Path, fileName: String, content: String): Path {
        failIfAsked()
        return fileSystem.createFile(directory, fileName, content)
    }

    override fun rename(path: Path, newName: String): Path {
        failIfAsked()
        return fileSystem.rename(path, newName)
    }

    override fun delete(path: Path): DeleteOutcome {
        failIfAsked()
        if (supportsTrash) trashed.add(path)
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        return if (supportsTrash) DeleteOutcome.MOVED_TO_TRASH else DeleteOutcome.DELETED
    }

    override fun revealInFileManager(path: Path) {
        revealed.add(path)
    }

    override fun openExternally(path: Path) {
        openedExternally.add(path)
    }

    private fun failIfAsked() {
        failNext?.let {
            failNext = null
            throw it
        }
    }

    /** No platform actions: the recording happens above. */
    private object NoDesktopActions : DesktopActions {
        override fun supports(action: DesktopAction): Boolean = false

        override fun moveToTrash(path: Path): Boolean = false

        override fun reveal(path: Path) = Unit

        override fun open(path: Path) = Unit
    }
}

/** A [FolderChooser] answering [answer] and recording its calls. */
class FakeFolderChooser(var answer: Path? = null) : FolderChooser {
    val calls: MutableList<Pair<String, Path?>> = CopyOnWriteArrayList()

    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? {
        calls.add(title to initialDirectory)
        return answer
    }
}

/** A [SettingsRepository] decorator counting the writes ([update] calls). */
class CountingSettingsRepository(private val delegate: SettingsRepository) : SettingsRepository {
    @Volatile
    var updates: Int = 0
        private set

    override val settings: StateFlow<IdeSettings> get() = delegate.settings

    override suspend fun load(): IdeSettings = delegate.load()

    override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings {
        updates++
        return delegate.update(transform)
    }
}

/** A clipboard in memory. */
class TestClipboard(var content: String? = null) : ClipboardPort {
    override fun getText(): String? = content

    override fun setText(text: String) {
        content = text
    }
}

/** The real [TextFileStore] that fails reads of [failingReads] and writes of [failingWrites] on demand. */
class ControllableFileStore(private val delegate: TextFileStore = TextFileStore()) : TextFileStore {
    val failingReads: MutableSet<Path> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val failingWrites: MutableSet<Path> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun read(path: Path): DecodedText {
        if (path.toAbsolutePath().normalize() in failingReads) throw IOException("cannot read $path")
        return delegate.read(path)
    }

    override fun write(path: Path, content: String, withBom: Boolean) {
        if (path.toAbsolutePath().normalize() in failingWrites) throw IOException("disk full")
        delegate.write(path, content, withBom)
    }
}
