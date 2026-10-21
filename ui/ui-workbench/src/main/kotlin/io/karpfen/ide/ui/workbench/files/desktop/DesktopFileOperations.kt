package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.FolderChooser
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.UUID

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
 * escape the directory. A rename that only changes the case of the name goes through a temporary sibling name: on a
 * case-insensitive file system (the macOS and Windows defaults) the new name denotes the same file, and moving a file
 * onto itself renames nothing.
 *
 * [openExternally] keeps the port's promise of failing with an `IOException` only: `java.awt.Desktop` reports a file
 * that does not exist (deleted since the tree or the problem showing it was updated) with an `IllegalArgumentException`,
 * which becomes a [NoSuchFileException]; a refusal of the platform (`SecurityException`, `UnsupportedOperationException`)
 * becomes an `IOException` with it as the cause.
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

    override fun rename(path: Path, newName: String): Path {
        val target = path.resolveSibling(checkedName(newName))
        if (isSameFileUnderAnotherName(path, target)) return renameThroughTemporaryName(path, target)
        return Files.move(path, target)
    }

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
        if (!supportsOpenExternally) return
        try {
            desktop.open(path)
        } catch (e: IllegalArgumentException) {
            throw NoSuchFileException(path.toString(), null, "the file does not exist").apply { initCause(e) }
        } catch (e: SecurityException) {
            throw IOException("Opening $path was refused: ${e.message}", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("Opening $path is not supported: ${e.message}", e)
        }
    }

    private fun isSameFileUnderAnotherName(path: Path, target: Path): Boolean =
        path.fileName != target.fileName &&
            Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            Files.isSameFile(path, target)

    /**
     * Renames [path] to [target], which names the same file (another spelling on a case-insensitive file system), in
     * two moves; if the second fails (e.g. [target] is a hard link of the file on a case-sensitive one), the first is
     * undone.
     */
    private fun renameThroughTemporaryName(path: Path, target: Path): Path {
        val temporary = Files.move(path, path.resolveSibling(".${path.fileName}.renaming-${UUID.randomUUID()}"))
        return try {
            Files.move(temporary, target)
        } catch (e: IOException) {
            Files.move(temporary, path)
            throw e
        }
    }

    private fun checkedName(name: String): String {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw IOException("'$name' is not a valid file name")
        }
        return name
    }
}
