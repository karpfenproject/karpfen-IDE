package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * A karpfen file opened for editing (spec §4): its [session] plus the file-level state the workbench needs (dirty
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
     * Resolves the current [conflict]: [ConflictResolution.RELOAD] replaces the text with the disk content as one
     * undoable `External` change and marks the save point; [ConflictResolution.KEEP_MINE] keeps the buffer (the next
     * save overwrites the disk). No-op without a conflict.
     * @throws IllegalArgumentException for RELOAD of a file deleted on disk.
     */
    public suspend fun resolveConflict(resolution: ConflictResolution)
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
    /** Discard the buffer and load the disk content. */
    RELOAD,

    /** Keep the buffer; the conflict is cleared. */
    KEEP_MINE,
}
