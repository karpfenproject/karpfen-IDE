package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * A karpfen file opened for editing: its [session] plus the file-level state the workbench needs (dirty
 * flag, line separator, external-change conflict). Created and closed by the [DocumentManager].
 */
public interface OpenDocument {

    /** Identity (absolute, normalized path). */
    public val id: DocumentId

    /** The file path. */
    public val path: Path get() = id.path

    /** Role of the file (KMETA, EVENTS_KMETA, KMODEL, KSTATES). */
    public val fileKind: FileKind

    /** Tokens, parse outcome and diagnostics of the edited text. */
    public val session: DocumentSession

    /** True iff the text differs from the last saved/loaded state (`!UndoManager.isAtSavePoint`). */
    public val isDirty: StateFlow<Boolean>

    /** The separator the file uses on disk; restored on save, updated on reload. */
    public val lineSeparator: StateFlow<LineSeparator>

    /**
     * Set when the file changed on disk while the document was dirty, or was deleted on disk; the UI offers
     * Reload / Keep mine. Null otherwise (a clean document is reloaded automatically as one undoable
     * `EditOrigin.External` change).
     */
    public val conflict: StateFlow<ExternalChange?>

    /**
     * Resolves the current [conflict] and reports what happened; never throws (I/O errors are values).
     * [ConflictResolution.RELOAD] takes the disk state: it replaces the text with the disk content as one undoable
     * `External` change, marks the save point and replaces the FILE findings (see [DocumentManager]) →
     * [ConflictResult.Reloaded]; for a file that no longer exists on disk it changes nothing and returns
     * [ConflictResult.DeletedOnDisk] (the caller decides: the workbench closes the tab without saving); a read failure
     * keeps buffer and conflict → [ConflictResult.Failed]. [ConflictResolution.KEEP_MINE] keeps the buffer and clears the
     * conflict; the document is dirty until the next save overwrites or recreates the file (no undo state matches the
     * disk, `UndoManager.invalidateSavePoint`) → [ConflictResult.Kept]. Without a conflict nothing happens →
     * [ConflictResult.NoConflict].
     */
    public suspend fun resolveConflict(resolution: ConflictResolution): ConflictResult
}

/** Outcome of [OpenDocument.resolveConflict]. */
public sealed interface ConflictResult {
    /** The buffer now holds the disk content. */
    public data object Reloaded : ConflictResult

    /** The buffer was kept; the conflict is cleared. */
    public data object Kept : ConflictResult

    /** RELOAD of a file deleted on disk: nothing was loaded, buffer and conflict are unchanged. */
    public data object DeletedOnDisk : ConflictResult

    /** Reading the disk content failed ([message]); buffer and conflict are unchanged. */
    public data class Failed(public val message: String) : ConflictResult

    /** There was no conflict; nothing happened. */
    public data object NoConflict : ConflictResult
}

/** A change on disk that conflicts with an open document. */
public data class ExternalChange(public val path: Path, public val kind: ExternalChangeKind)

/** Kind of an [ExternalChange]. */
public enum class ExternalChangeKind {
    /** The file content changed on disk. */
    MODIFIED,

    /** The file was deleted on disk. */
    DELETED,
}

/** User decision for an [ExternalChange]. */
public enum class ConflictResolution {
    /**
     * Take the disk state: discard the buffer and load the disk content (banner "Reload"); for a file deleted on disk
     * there is nothing to load and the workbench closes the tab (banner "Close", see [ConflictResult.DeletedOnDisk]).
     */
    RELOAD,

    /** Keep the buffer; the conflict is cleared. */
    KEEP_MINE,
}
