package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import java.nio.file.Path

/**
 * The desktop [ProjectFileOperations] (java.nio + `java.awt.Desktop` for trash, reveal in the file manager and opening
 * externally). Headless-safe (MUST): creating it never touches the toolkit, and when
 * `GraphicsEnvironment.isHeadless()` or `!Desktop.isDesktopSupported()` (or the action is unsupported) the `supports*`
 * flags are false, `delete` deletes permanently and `revealInFileManager`/`openExternally` are no-ops — never a
 * `HeadlessException` (end-to-end tests run the production wiring headless).
 */
public fun desktopProjectFileOperations(): ProjectFileOperations = DesktopProjectFileOperations()

/**
 * The native [FolderChooser] (AWT `FileDialog` in directory mode on macOS, `JFileChooser` elsewhere). Headless-safe:
 * creating it never touches the toolkit, and when headless `chooseFolder` returns null.
 */
public fun nativeFolderChooser(): FolderChooser = AwtFolderChooser()

internal class DesktopProjectFileOperations : ProjectFileOperations {
    override val supportsTrash: Boolean get() = TODO("WP-WORKBENCH: Desktop.Action.MOVE_TO_TRASH support")
    override val supportsRevealInFileManager: Boolean get() = TODO("WP-WORKBENCH: Desktop.Action.BROWSE_FILE_DIR support")
    override val supportsOpenExternally: Boolean get() = TODO("WP-WORKBENCH: Desktop.Action.OPEN support")

    override fun createFile(directory: Path, fileName: String, content: String): Path = TODO("WP-WORKBENCH: create file")

    override fun rename(path: Path, newName: String): Path = TODO("WP-WORKBENCH: rename")

    override fun delete(path: Path): DeleteOutcome = TODO("WP-WORKBENCH: delete or move to trash")

    override fun revealInFileManager(path: Path): Unit = TODO("WP-WORKBENCH: reveal in file manager")

    override fun openExternally(path: Path): Unit = TODO("WP-WORKBENCH: open in the default application")
}

internal class AwtFolderChooser : FolderChooser {
    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? = TODO("WP-WORKBENCH: folder dialog")
}
