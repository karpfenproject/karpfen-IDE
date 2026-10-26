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
 * File operations of the project tree: create, rename (files and directories), delete (to the trash when the platform
 * supports it), reveal in Finder/Explorer and opening files the editor does not support in the system's default
 * application. A port (Compose- and AWT-free); the desktop adapter lives in `files.desktop`. Blocking: the workbench
 * calls it on `WorkbenchServices.ioDispatcher`. The workspace notices the changes through its watcher or an explicit
 * refresh. Naming: "reveal" alone means "make visible in this component" throughout the UI modules; showing a file in
 * the file manager always names the file manager.
 */
public interface ProjectFileOperations {

    /** True if [delete] moves files to the trash on this platform. */
    public val supportsTrash: Boolean

    /** True if [revealInFileManager] is supported on this platform. */
    public val supportsRevealInFileManager: Boolean

    /** True if [openExternally] is supported on this platform. */
    public val supportsOpenExternally: Boolean

    /**
     * Creates [fileName] in [directory] with [content] and returns its path.
     * @throws java.io.IOException if it exists or cannot be written.
     */
    public fun createFile(directory: Path, fileName: String, content: String): Path

    /**
     * Renames [path] to [newName] in the same directory and returns the new path, also when [newName] only changes the
     * case of the name on a case-insensitive file system (the name on disk changes).
     * @throws java.io.IOException if the target exists or the move fails.
     */
    public fun rename(path: Path, newName: String): Path

    /**
     * Deletes [path] (recursively for directories): to the trash if supported, otherwise permanently.
     * @throws java.io.IOException if it fails.
     */
    public fun delete(path: Path): DeleteOutcome

    /** Shows [path] in the platform file manager (Finder/Explorer); no-op if unsupported. */
    public fun revealInFileManager(path: Path)

    /**
     * Opens [path] in the system's default application (the files the editor cannot open, such as bridge
     * configurations); no-op if unsupported.
     * @throws java.io.IOException if the platform fails to open it, also when it does not exist
     *   (`java.nio.file.NoSuchFileException`); never an unchecked exception of the platform.
     */
    public fun openExternally(path: Path)
}

/** How [ProjectFileOperations.delete] removed a file. */
public enum class DeleteOutcome {
    /** Moved to the trash. */
    MOVED_TO_TRASH,

    /** Deleted permanently. */
    DELETED,
}

/** Asks the user for a folder (native dialog). A port; the desktop adapter lives in `files.desktop`. */
public fun interface FolderChooser {
    /**
     * Shows the dialog with [title], starting in [initialDirectory]; null if cancelled. Returns or throws in every case
     * (never stays suspended after the dialog is gone).
     * @throws java.io.IOException if the dialog fails.
     */
    public suspend fun chooseFolder(title: String, initialDirectory: Path?): Path?
}
