package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Resolves the conflicts of open documents ([OpenDocument.resolveConflict] delegates here, so the document stays a
 * state holder without I/O).
 */
internal fun interface ConflictResolver {
    /** Resolves the conflict of [document] with [resolution]; never throws. */
    suspend fun resolve(document: DefaultOpenDocument, resolution: ConflictResolution): ConflictResult
}

/**
 * Default [OpenDocument]: the file-level state of one open document. A state holder only — loading, saving and
 * conflict handling are done by the document manager's collaborators ([DiskSynchronizer], [DocumentSaver]), which
 * mutate it on the single writer (see [SingleWriter]).
 *
 * The disk state is the [LoadedSource] the buffer was last synchronized with (opened, saved, reloaded, or acknowledged
 * with Keep mine), or null when the file is known to be absent; an external change is a disk content that differs from
 * it, which also makes the watch event of the document's own save a no-op (spec §4).
 *
 * @param scope the document's own scope (parent of the session's scope), cancelled by [close].
 */
internal class DefaultOpenDocument(
    override val session: DocumentSession,
    override val fileKind: FileKind,
    loaded: LoadedSource,
    private val scope: CoroutineScope,
    private val conflicts: ConflictResolver,
) : OpenDocument {

    private val separatorState = MutableStateFlow(loaded.lineSeparator)
    private val conflictState = MutableStateFlow<ExternalChange?>(null)

    override val id: DocumentId get() = session.id

    /** The edited text (the session's). */
    val text: EditableText get() = session.text

    /**
     * Derived from the save point; collected unconfined, so it is updated synchronously with every edit and save point
     * (a dirty check right after an edit never sees a stale value).
     */
    override val isDirty: StateFlow<Boolean> = text.undo.isAtSavePoint
        .map { atSavePoint -> !atSavePoint }
        .stateIn(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), SharingStarted.Eagerly, !text.undo.isAtSavePoint.value)

    override val lineSeparator: StateFlow<LineSeparator> = separatorState.asStateFlow()

    override val conflict: StateFlow<ExternalChange?> = conflictState.asStateFlow()

    /** True if a byte order mark is restored on save. Writer only. */
    var hasBom: Boolean = loaded.hasBom
        private set

    /** The disk state the buffer is synchronized with, null if the file is known to be absent. Writer only. */
    var diskState: LoadedSource? = loaded
        private set

    /** The disk state that raised the current [conflict] (null for a deletion). Writer only. */
    private var observedDiskState: LoadedSource? = null

    /** True if the text differs from its save point, read from the undo manager itself (never stale). */
    val isDirtyNow: Boolean get() = !text.undo.isAtSavePoint.value

    override suspend fun resolveConflict(resolution: ConflictResolution): ConflictResult = conflicts.resolve(this, resolution)

    /** The buffer now equals [source] on disk (opened, reloaded or saved): adopts its separator and BOM, clears the conflict. */
    fun synchronizedWith(source: LoadedSource) {
        diskState = source
        separatorState.value = source.lineSeparator
        hasBom = source.hasBom
        observedDiskState = null
        conflictState.value = null
    }

    /** A save wrote the buffer; the disk now holds [written] (the self-induced watch event will find it unchanged). */
    fun saved(written: LoadedSource) {
        diskState = written
        observedDiskState = null
        conflictState.value = null
    }

    /** The disk changed to [observed] (null: deleted) while the buffer could not follow; the UI offers a resolution. */
    fun raiseConflict(kind: ExternalChangeKind, observed: LoadedSource?) {
        observedDiskState = observed
        conflictState.value = ExternalChange(path, kind)
    }

    /** The disk is back in the known [diskState]; a pending conflict is void. */
    fun clearConflict() {
        observedDiskState = null
        conflictState.value = null
    }

    /**
     * Keep mine: the buffer stays, the observed disk state (null for a deletion) becomes the known one. No state of the
     * undo history matches that disk state, so the save point is invalidated: the document stays dirty until a save
     * overwrites or recreates the file, is covered by Save All and the unsaved-changes prompt, and a later change on
     * disk is a conflict, never a silent reload over the kept buffer.
     */
    fun keepMine() {
        diskState = observedDiskState
        text.undo.invalidateSavePoint()
        clearConflict()
    }

    /** Stops the session and every background job of this document. */
    fun close() {
        session.close()
        scope.cancel()
    }
}
