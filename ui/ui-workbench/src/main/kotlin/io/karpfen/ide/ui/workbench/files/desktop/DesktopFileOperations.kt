package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator

/**
 * The desktop [ProjectFileOperations] (java.nio + `java.awt.Desktop` for trash, reveal in the file manager and opening
 * externally). Headless-safe (MUST): creating it never touches the toolkit, and when
 * `GraphicsEnvironment.isHeadless()` or `!Desktop.isDesktopSupported()` (or the action is unsupported) the `supports*`
 * flags are false, `delete` deletes permanently and `revealInFileManager`/`openExternally` are no-ops — never a
 * `HeadlessException` (end-to-end tests run the production wiring headless).
 */
public fun desktopProjectFileOperations(): ProjectFileOperations = DesktopProjectFileOperations(AwtDesktopActions())

/**
 * The native [FolderChooser] (AWT `FileDialog` in directory mode on macOS, `JFileChooser` elsewhere). Headless-safe:
 * creating it never touches the toolkit, and when headless `chooseFolder` returns null.
 */
public fun nativeFolderChooser(): FolderChooser = AwtFolderChooser()

/**
 * [ProjectFileOperations] on java.nio, with the platform actions (trash, reveal, open) delegated to [desktop]. Names
 * given to [createFile] and [rename] must be plain file names (no path separators, not `.` or `..`), so a name can never
 * escape the directory.
 */
internal class DesktopProjectFileOperations(private val desktop: DesktopActions) : ProjectFileOperations {

    override val supportsTrash: Boolean get() = desktop.supports(DesktopAction.MOVE_TO_TRASH)
    override val supportsRevealInFileManager: Boolean get() = desktop.supports(DesktopAction.REVEAL)
    override val supportsOpenExternally: Boolean get() = desktop.supports(DesktopAction.OPEN)

    override fun createFile(directory: Path, fileName: String, content: String): Path {
        val target = directory.resolve(checkedName(fileName))
        Files.writeString(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return target
    }

    override fun rename(path: Path, newName: String): Path = Files.move(path, path.resolveSibling(checkedName(newName)))

    override fun delete(path: Path): DeleteOutcome {
        if (!Files.exists(path)) throw NoSuchFileException(path.toString())
        if (supportsTrash) {
            if (!desktop.moveToTrash(path)) throw IOException("Could not move $path to the trash")
            return DeleteOutcome.MOVED_TO_TRASH
        }
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        return DeleteOutcome.DELETED
    }

    override fun revealInFileManager(path: Path) {
        if (supportsRevealInFileManager) desktop.reveal(path)
    }

    override fun openExternally(path: Path) {
        if (supportsOpenExternally) desktop.open(path)
    }

    private fun checkedName(name: String): String {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw IOException("'$name' is not a valid file name")
        }
        return name
    }
}
