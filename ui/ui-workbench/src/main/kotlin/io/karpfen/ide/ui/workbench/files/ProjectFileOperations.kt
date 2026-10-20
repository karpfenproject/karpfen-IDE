package io.karpfen.ide.ui.workbench.files

import java.nio.file.Path

/**
 * File operations of the project tree (spec §8): create, rename, delete (to the trash when the platform supports it)
 * and reveal in Finder/Explorer. Blocking; called on an IO dispatcher. The workspace notices the changes through its
 * watcher or an explicit refresh.
 */
public interface ProjectFileOperations {

    /** True if [delete] moves files to the trash on this platform. */
    public val supportsTrash: Boolean

    /** True if [reveal] is supported on this platform. */
    public val supportsReveal: Boolean

    /**
     * Creates [fileName] in [directory] with [content] and returns its path.
     * @throws java.io.IOException if it exists or cannot be written.
     */
    public fun createFile(directory: Path, fileName: String, content: String): Path

    /**
     * Renames [path] to [newName] in the same directory and returns the new path.
     * @throws java.io.IOException if the target exists or the move fails.
     */
    public fun rename(path: Path, newName: String): Path

    /**
     * Deletes [path] (recursively for directories): to the trash if supported, otherwise permanently.
     * @throws java.io.IOException if it fails.
     */
    public fun delete(path: Path): DeleteOutcome

    /** Shows [path] in the platform file manager; no-op if unsupported. */
    public fun reveal(path: Path)
}

/** How [ProjectFileOperations.delete] removed a file. */
public enum class DeleteOutcome {
    /** Moved to the trash. */
    MOVED_TO_TRASH,

    /** Deleted permanently. */
    DELETED,
}

/** The desktop implementation (java.nio + `java.awt.Desktop` for trash and reveal). */
public fun ProjectFileOperations(): ProjectFileOperations = DesktopProjectFileOperations()

/** Asks the user for a folder (native dialog). */
public fun interface FolderChooser {
    /** Shows the dialog with [title], starting in [initialDirectory]; null if cancelled. */
    public suspend fun chooseFolder(title: String, initialDirectory: Path?): Path?
}

/** The native folder chooser (AWT `FileDialog` in directory mode on macOS, `JFileChooser` elsewhere). */
public fun FolderChooser(): FolderChooser = AwtFolderChooser()

internal class DesktopProjectFileOperations : ProjectFileOperations {
    override val supportsTrash: Boolean get() = TODO("WP-WORKBENCH: Desktop.Action.MOVE_TO_TRASH support")
    override val supportsReveal: Boolean get() = TODO("WP-WORKBENCH: Desktop.Action.BROWSE_FILE_DIR support")

    override fun createFile(directory: Path, fileName: String, content: String): Path = TODO("WP-WORKBENCH: create file")

    override fun rename(path: Path, newName: String): Path = TODO("WP-WORKBENCH: rename")

    override fun delete(path: Path): DeleteOutcome = TODO("WP-WORKBENCH: delete or move to trash")

    override fun reveal(path: Path): Unit = TODO("WP-WORKBENCH: reveal in file manager")
}

internal class AwtFolderChooser : FolderChooser {
    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? = TODO("WP-WORKBENCH: folder dialog")
}
