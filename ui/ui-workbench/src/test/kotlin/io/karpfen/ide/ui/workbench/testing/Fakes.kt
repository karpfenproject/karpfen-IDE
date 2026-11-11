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

/** A [FileWatcher] that emits the changes a test reports. The real watch service polls slowly on macOS. */
class FakeFileWatcher : FileWatcher {
    private val changes = MutableSharedFlow<FileChange>(extraBufferCapacity = 1024)

    override fun watch(root: Path, isIgnored: (Path) -> Boolean): Flow<FileChange> = changes.filterNot { isIgnored(it.path) }

    /** Reports a change of [path]. */
    fun report(path: Path, kind: FileChangeKind = FileChangeKind.MODIFIED) {
        check(changes.tryEmit(FileChange(path.toAbsolutePath().normalize(), kind)))
    }
}

/**
 * [ProjectFileOperations] on the real file system that records the platform calls instead of making them. The platform
 * calls are moving to the trash, revealing in the file manager and opening in the default application. Setting
 * [failNext] makes the next create, rename or delete throw.
 *
 * Creating and renaming go through the production adapter `DesktopProjectFileOperations` without platform actions, so
 * its case-only renames apply here too. Opening externally is recorded and then made through that adapter on
 * [platform]. By default [platform] makes no actions at all.
 */
class RecordingFileOperations internal constructor(
    override val supportsTrash: Boolean,
    override val supportsRevealInFileManager: Boolean,
    override val supportsOpenExternally: Boolean,
    platform: DesktopActions,
) : ProjectFileOperations {

    /** Recording operations with the given platform support and no platform actions at all. */
    constructor(
        supportsTrash: Boolean = false,
        supportsRevealInFileManager: Boolean = true,
        supportsOpenExternally: Boolean = true,
    ) : this(supportsTrash, supportsRevealInFileManager, supportsOpenExternally, NoDesktopActions)

    /** Recording operations whose opening externally goes through the production adapter on [platform]. */
    internal constructor(platform: DesktopActions) : this(false, true, true, platform)

    /** The paths revealed in the file manager, in call order. */
    val revealed: MutableList<Path> = CopyOnWriteArrayList()

    /** The paths opened in the default application, in call order. */
    val openedExternally: MutableList<Path> = CopyOnWriteArrayList()

    /** The paths deleted while the trash is supported, in call order. */
    val trashed: MutableList<Path> = CopyOnWriteArrayList()

    /** Thrown by the next create, rename or delete, then cleared. */
    var failNext: IOException? = null

    /** The production adapter that creates and renames on the file system. */
    private val fileSystem: ProjectFileOperations = DesktopProjectFileOperations(platform)

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
        fileSystem.openExternally(path)
    }

    /** Throws and clears [failNext] if it is set. */
    private fun failIfAsked() {
        failNext?.let {
            failNext = null
            throw it
        }
    }

    /** Platform actions that do nothing. [RecordingFileOperations] records the calls itself. */
    private object NoDesktopActions : DesktopActions {
        override fun supports(action: DesktopAction): Boolean = false

        override fun moveToTrash(path: Path): Boolean = false

        override fun reveal(path: Path) = Unit

        override fun open(path: Path) = Unit
    }
}

/**
 * [DesktopActions] that fail like `java.awt.Desktop` does on a desktop with JDK 21. Every action is supported. Opening
 * or revealing a file that does not exist throws `IllegalArgumentException("The file: … doesn't exist.")`. When
 * [failure] is set, every call throws it instead, for example a `SecurityException`. Nothing is opened.
 */
internal class JdkLikeDesktopActions(var failure: RuntimeException? = null) : DesktopActions {
    /** The paths opened, in call order. */
    val opened: MutableList<Path> = CopyOnWriteArrayList()

    override fun supports(action: DesktopAction): Boolean = true

    override fun moveToTrash(path: Path): Boolean {
        checkFile(path)
        return false
    }

    override fun reveal(path: Path) = checkFile(path)

    override fun open(path: Path) {
        checkFile(path)
        opened.add(path)
    }

    /**
     * Throws [failure] if it is set. Otherwise throws `IllegalArgumentException` for a missing [path], like the JDK.
     */
    private fun checkFile(path: Path) {
        failure?.let { throw it }
        if (!Files.exists(path)) throw IllegalArgumentException("The file: $path doesn't exist.")
    }
}

/** A [FolderChooser] that records its calls and answers [answer]. When [failure] is set, it throws that instead. */
class FakeFolderChooser(var answer: Path? = null, var failure: IOException? = null) : FolderChooser {
    /** The calls as title and initial directory, in call order. */
    val calls: MutableList<Pair<String, Path?>> = CopyOnWriteArrayList()

    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? {
        calls.add(title to initialDirectory)
        failure?.let { throw it }
        return answer
    }
}

/** A [SettingsRepository] decorator that counts the writes, which are the calls of [update]. */
class CountingSettingsRepository(private val delegate: SettingsRepository) : SettingsRepository {
    /** The number of [update] calls so far. */
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
    private var wholeLine = false

    override fun getText(): String? = content

    override fun setText(text: String, wholeLine: Boolean): Boolean {
        content = text
        this.wholeLine = wholeLine
        return true
    }

    override fun holdsWholeLine(): Boolean = wholeLine && content != null
}

/** The real [TextFileStore], except that reads of [failingReads] and writes of [failingWrites] fail. */
class ControllableFileStore(private val delegate: TextFileStore = TextFileStore()) : TextFileStore {
    /** Normalized paths whose reads fail with an `IOException`. */
    val failingReads: MutableSet<Path> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Normalized paths whose writes fail with an `IOException` that reports a full disk. */
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
