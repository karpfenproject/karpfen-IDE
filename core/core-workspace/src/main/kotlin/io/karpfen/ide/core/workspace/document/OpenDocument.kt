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
package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * A karpfen file opened for editing. The file is a modeling-language source or an environment descriptor. The open
 * document holds its [session] and the file-level state the workbench needs: the dirty flag, the line separator and
 * the conflict with an external change. The [DocumentManager] creates and closes it.
 */
public interface OpenDocument {

    /** The identity, which is the absolute, normalized path. */
    public val id: DocumentId

    /** The file path. */
    public val path: Path get() = id.path

    /** The role of the file: KMETA, EVENTS_KMETA, KMODEL, KSTATES or ENV_DESCRIPTOR. */
    public val fileKind: FileKind

    /** The tokens, the parse outcome and the diagnostics of the edited text. */
    public val session: DocumentSession

    /**
     * True if and only if the text differs from the last saved or loaded state. It is the negation of
     * `UndoManager.isAtSavePoint`.
     */
    public val isDirty: StateFlow<Boolean>

    /** The line separator the file uses on disk. It is restored on save and updated on reload. */
    public val lineSeparator: StateFlow<LineSeparator>

    /**
     * Set when the file changed on disk while the document was dirty, or when the file was deleted on disk. The UI then
     * offers Reload and Keep mine. Otherwise it is null. A clean document is reloaded automatically as one undoable
     * `EditOrigin.External` change.
     */
    public val conflict: StateFlow<ExternalChange?>

    /**
     * Resolves the current [conflict] and reports what happened. It never throws, because I/O errors are returned as
     * values.
     *
     * [ConflictResolution.RELOAD] takes the disk state. It replaces the text with the disk content as one undoable
     * `External` change, marks the save point and replaces the FILE findings as described on [DocumentManager]. It
     * returns [ConflictResult.Reloaded]. For a file that no longer exists on disk it changes nothing and returns
     * [ConflictResult.DeletedOnDisk]. The caller decides what to do then, and the workbench closes the tab without
     * saving. A read failure keeps the buffer and the conflict and returns [ConflictResult.Failed].
     *
     * [ConflictResolution.KEEP_MINE] keeps the buffer, clears the conflict and returns [ConflictResult.Kept]. The
     * document stays dirty until the next save overwrites or recreates the file. No undo state matches the disk, so
     * `UndoManager.invalidateSavePoint` is called.
     *
     * Without a conflict nothing happens, and it returns [ConflictResult.NoConflict].
     */
    public suspend fun resolveConflict(resolution: ConflictResolution): ConflictResult
}

/** Outcome of [OpenDocument.resolveConflict]. */
public sealed interface ConflictResult {
    /** The buffer now holds the disk content. */
    public data object Reloaded : ConflictResult

    /** The buffer was kept, and the conflict is cleared. */
    public data object Kept : ConflictResult

    /**
     * A reload of a file that was deleted on disk. Nothing was loaded, and the buffer and the conflict are unchanged.
     */
    public data object DeletedOnDisk : ConflictResult

    /** Reading the disk content failed, as [message] says. The buffer and the conflict are unchanged. */
    public data class Failed(public val message: String) : ConflictResult

    /** There was no conflict, so nothing happened. */
    public data object NoConflict : ConflictResult
}

/** A change on disk that conflicts with an open document. */
public data class ExternalChange(public val path: Path, public val kind: ExternalChangeKind)

/** The kind of an [ExternalChange]. */
public enum class ExternalChangeKind {
    /** The file content changed on disk. */
    MODIFIED,

    /** The file was deleted on disk. */
    DELETED,
}

/** The decision of the user for an [ExternalChange]. */
public enum class ConflictResolution {
    /**
     * Take the disk state. The buffer is discarded and the disk content is loaded. The banner calls this "Reload". For
     * a file deleted on disk there is nothing to load, and the workbench closes the tab. The banner then calls it
     * "Close", and the result is [ConflictResult.DeletedOnDisk].
     */
    RELOAD,

    /** Keep the buffer. The conflict is cleared. */
    KEEP_MINE,
}
