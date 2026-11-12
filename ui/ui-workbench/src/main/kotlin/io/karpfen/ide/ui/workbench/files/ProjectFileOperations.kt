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
package io.karpfen.ide.ui.workbench.files

import java.nio.file.Path

/**
 * The file operations of the project tree. They create files, rename files and directories, and delete them. Deleting
 * moves to the trash where the platform supports it. They also show a file in Finder or Explorer, and they open files
 * that the editor does not support in the system's default application.
 *
 * This is a port and depends on neither Compose nor AWT. The desktop adapter lives in the package `files.desktop`. All
 * calls block, so the workbench calls them on `WorkbenchServices.ioDispatcher`. The workspace notices the changes
 * through its file watcher or through an explicit refresh.
 *
 * Throughout the UI modules, "reveal" alone means "make visible in this component". A name that shows a file in the
 * file manager always says "file manager".
 */
public interface ProjectFileOperations {

    /** True if [delete] moves files to the trash on this platform. */
    public val supportsTrash: Boolean

    /** True if [revealInFileManager] is supported on this platform. */
    public val supportsRevealInFileManager: Boolean

    /** True if [openExternally] is supported on this platform. */
    public val supportsOpenExternally: Boolean

    /**
     * Creates the file [fileName] in [directory] with [content] and returns its path.
     *
     * @throws java.io.IOException if the file exists or cannot be written.
     */
    public fun createFile(directory: Path, fileName: String, content: String): Path

    /**
     * Renames [path] to [newName] in the same directory and returns the new path. This also works when [newName] only
     * changes the case of the name on a case-insensitive file system. The name on disk then changes as well.
     *
     * @throws java.io.IOException if the target exists or the move fails.
     */
    public fun rename(path: Path, newName: String): Path

    /**
     * Deletes [path], and for a directory everything below it. The file goes to the trash if the platform supports
     * that. Otherwise it is deleted for good.
     *
     * @throws java.io.IOException if the deletion fails.
     */
    public fun delete(path: Path): DeleteOutcome

    /** Shows [path] in the platform file manager, such as Finder or Explorer. Does nothing if that is not supported. */
    public fun revealInFileManager(path: Path)

    /**
     * Opens [path] in the system's default application. The workbench uses this for files the editor cannot open, such
     * as a README. Does nothing if the platform does not support it.
     *
     * @throws java.io.IOException if the platform fails to open the file. A file that does not exist leads to a
     *   `java.nio.file.NoSuchFileException`. Unchecked exceptions of the platform never escape.
     */
    public fun openExternally(path: Path)
}

/** How [ProjectFileOperations.delete] removed a file. */
public enum class DeleteOutcome {
    /** The file was moved to the trash. */
    MOVED_TO_TRASH,

    /** The file was deleted for good. */
    DELETED,
}

/**
 * Asks the user for a folder in a native dialog. This is a port. The desktop adapter lives in the package
 * `files.desktop`.
 */
public fun interface FolderChooser {
    /**
     * Shows the dialog with [title], starting in [initialDirectory]. Returns the chosen folder, or null if the user
     * cancelled. The call returns or throws in every case. It never stays suspended after the dialog is gone.
     *
     * @throws java.io.IOException if the dialog fails.
     */
    public suspend fun chooseFolder(title: String, initialDirectory: Path?): Path?
}
