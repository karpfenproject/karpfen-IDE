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
 * Resolves the conflicts of open documents. [OpenDocument.resolveConflict] delegates here, so the document stays a
 * state holder without I/O.
 */
internal fun interface ConflictResolver {
    /** Resolves the conflict of [document] with [resolution]. It never throws. */
    suspend fun resolve(document: DefaultOpenDocument, resolution: ConflictResolution): ConflictResult
}

/**
 * Default [OpenDocument]. It holds the file-level state of one open document and nothing else. Loading, saving and
 * conflict handling are done by the collaborators of the document manager, [DiskSynchronizer] and [DocumentSaver].
 * They change the document on the single writer described on [SingleWriter].
 *
 * The disk state is the [LoadedSource] that the buffer was last synchronized with. That happens when the document is
 * opened, saved or reloaded, or when a conflict is acknowledged with Keep mine. The disk state is null when the file
 * is known to be absent. An external change is disk content that differs from the disk state, as [knowsContentOf]
 * decides. The same check makes the watch event of the document's own save do nothing.
 *
 * Members marked "writer only" may only be used on the single writer.
 *
 * @param scope the document's own scope, the parent of the session's scope. [close] cancels it.
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

    /** The edited text, which is the text of the session. */
    val text: EditableText get() = session.text

    /**
     * Derived from the save point. It is collected unconfined, so it is updated synchronously with every edit and every
     * save point. A dirty check right after an edit never sees a stale value.
     */
    override val isDirty: StateFlow<Boolean> = text.undo.isAtSavePoint
        .map { atSavePoint -> !atSavePoint }
        .stateIn(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), SharingStarted.Eagerly, !text.undo.isAtSavePoint.value)

    override val lineSeparator: StateFlow<LineSeparator> = separatorState.asStateFlow()

    override val conflict: StateFlow<ExternalChange?> = conflictState.asStateFlow()

    /** True if a byte order mark is restored on save. Writer only. */
    var hasBom: Boolean = loaded.hasBom
        private set

    /** The disk state the buffer is synchronized with, or null if the file is known to be absent. Writer only. */
    var diskState: LoadedSource? = loaded
        private set

    /** The disk state that raised the current [conflict]. It is null for a deletion. Writer only. */
    private var observedDiskState: LoadedSource? = null

    /**
     * True if [source] holds the content of the [diskState], which means the same text, line separator and byte order
     * mark. Findings are derived from the content and take no part. So a loader whose findings a save cannot foresee
     * still recognizes the watch event of the save itself. Writer only.
     */
    fun knowsContentOf(source: LoadedSource): Boolean =
        diskState?.let { it.text == source.text && it.lineSeparator == source.lineSeparator && it.hasBom == source.hasBom } == true

    /** True if the text differs from its save point. It is read from the undo manager itself and is never stale. */
    val isDirtyNow: Boolean get() = !text.undo.isAtSavePoint.value

    override suspend fun resolveConflict(resolution: ConflictResolution): ConflictResult = conflicts.resolve(this, resolution)

    /**
     * The buffer now equals [source] on disk, after an open, a reload or a save. The document adopts its line separator
     * and byte order mark and clears the conflict.
     */
    fun synchronizedWith(source: LoadedSource) {
        diskState = source
        separatorState.value = source.lineSeparator
        hasBom = source.hasBom
        observedDiskState = null
        conflictState.value = null
    }

    /**
     * A save wrote the buffer, and the disk now holds [written]. The watch event that the save causes will find the
     * file unchanged.
     */
    fun saved(written: LoadedSource) {
        diskState = written
        observedDiskState = null
        conflictState.value = null
    }

    /**
     * The disk changed to [observed] while the buffer could not follow. [observed] is null when the file was deleted.
     * The UI offers a resolution.
     */
    fun raiseConflict(kind: ExternalChangeKind, observed: LoadedSource?) {
        observedDiskState = observed
        conflictState.value = ExternalChange(path, kind)
    }

    /** The disk is back in the known [diskState], so a pending conflict no longer applies. */
    fun clearConflict() {
        observedDiskState = null
        conflictState.value = null
    }

    /**
     * Resolves a conflict with Keep mine. The buffer stays, and the observed disk state becomes the known one. For a
     * deletion that state is null. No state of the undo history matches that disk state, so the save point is
     * invalidated. The document then stays dirty until a save overwrites or recreates the file. Save All and the
     * unsaved-changes prompt cover it. A later change on disk is a conflict and never a silent reload over the kept
     * buffer.
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
