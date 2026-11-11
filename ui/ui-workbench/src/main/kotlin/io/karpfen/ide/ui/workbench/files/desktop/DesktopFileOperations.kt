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
 * Creates the desktop [ProjectFileOperations]. They use java.nio for the files and `java.awt.Desktop` for the trash,
 * for showing a file in the file manager and for opening a file externally.
 *
 * They must be safe in a headless JVM, because the end-to-end tests run the production wiring headless. Creating them
 * never touches the toolkit. When `GraphicsEnvironment.isHeadless()` is true, when `Desktop.isDesktopSupported()` is
 * false, or when the platform does not support an action, the matching `supports` flags are false. `delete` then
 * deletes for good, and `revealInFileManager` and `openExternally` do nothing. They never throw a `HeadlessException`.
 */
public fun desktopProjectFileOperations(): ProjectFileOperations = DesktopProjectFileOperations(AwtDesktopActions())

/**
 * Creates the native [FolderChooser]. It is the AWT `FileDialog` in directory mode on macOS and a `JFileChooser` on
 * other platforms. It is safe in a headless JVM. Creating it never touches the toolkit, and `chooseFolder` returns null
 * when the JVM is headless.
 */
public fun nativeFolderChooser(): FolderChooser = AwtFolderChooser()

/**
 * [ProjectFileOperations] on java.nio. The platform actions, which are trash, reveal and open, are delegated to
 * [desktop].
 *
 * Names given to [createFile] and [rename] must be plain file names. They contain no path separator and are not `.` or
 * `..`, so a name can never escape its directory.
 *
 * A rename that only changes the case of the name goes through a temporary sibling name. On a case-insensitive file
 * system, which is the default on macOS and Windows, the new name denotes the same file. Moving a file onto itself
 * would rename nothing.
 *
 * [openExternally] keeps the promise of the port to fail with an `IOException` only. `java.awt.Desktop` reports a file
 * that does not exist with an `IllegalArgumentException`. That happens when the file was deleted after the tree or the
 * problem that shows it was last updated. It becomes a [NoSuchFileException]. A refusal of the platform, which is a
 * `SecurityException` or an `UnsupportedOperationException`, becomes an `IOException` with the refusal as its cause.
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

    /**
     * True if [target] is another spelling of the file at [path]. That is the case for a change of case only on a
     * case-insensitive file system.
     */
    private fun isSameFileUnderAnotherName(path: Path, target: Path): Boolean =
        path.fileName != target.fileName &&
            Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            Files.isSameFile(path, target)

    /**
     * Renames [path] to [target] in two moves. [target] names the same file, spelled differently on a case-insensitive
     * file system. If the second move fails, the first one is undone. The second move fails, for example, when
     * [target] is a hard link of the file on a case-sensitive file system.
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

    /**
     * Returns [name] if it is a plain file name. A plain file name is not blank, is not `.` or `..`, and contains no
     * path separator and no NUL character.
     *
     * @throws IOException if [name] is not a plain file name.
     */
    private fun checkedName(name: String): String {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw IOException("'$name' is not a valid file name")
        }
        return name
    }
}
