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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.session.ExternalDiagnosticSources
import io.karpfen.ide.core.text.HistoryState
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Saves open documents. The current snapshot is converted back to file form, with the document's line separator and
 * byte order mark. It is written atomically through the [fileStore] on [ioDispatcher]. Then three things happen on the
 * [writer].
 * - The written state becomes the save point. That is an undo checkpoint taken together with the snapshot. Typing
 *   during the write keeps the document dirty, because the disk holds the older text. Undoing that typing makes the
 *   document clean again.
 * - The disk state that the write leaves becomes the document's disk state. So the watch event that the write causes
 *   is recognized as the save's own, and a pending conflict is cleared, because the disk now holds the buffer. The
 *   state comes from the [sourceLoader] through `SourceLoader.loadWritten`. That is the single loading rule, so the
 *   saver never uses a rule of its own.
 * - The findings of that disk state become the FILE findings of the written snapshot. The default rule finds none,
 *   because the file is now valid UTF-8 with one kind of line separator.
 *
 * A failed write changes nothing and is reported as [SaveResult.Failed].
 */
internal class DocumentSaver(
    private val fileStore: TextFileStore,
    private val sourceLoader: SourceLoader,
    private val ioDispatcher: CoroutineDispatcher,
    private val writer: SingleWriter,
) {

    /** Saves [document]. It never throws, except for a cancellation. */
    suspend fun save(document: DefaultOpenDocument): SaveResult {
        val pending = writer.run { PendingWrite(document.text.snapshot.value, document.text.undo.checkpoint(), document) }
        try {
            withContext(ioDispatcher) { fileStore.write(document.path, pending.content, pending.withBom) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            logger.warn("Saving {} failed", document.path, e)
            return SaveResult.Failed(document.path, "Cannot save ${describeIoFailure(document.path, e)}")
        }
        val written = sourceLoader.loadWritten(document.path, pending.content, pending.withBom)
        writer.run {
            document.text.undo.markSavePoint(pending.state)
            document.saved(written)
            document.session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, pending.snapshot, findingsOf(written, pending))
        }
        return SaveResult.Saved(document.path, pending.snapshot.version)
    }

    /**
     * The FILE findings of the [written] disk state for the snapshot of [pending]. Their ranges refer to the written
     * text. That is the text of the snapshot, unless the write changed it, as it does when it writes an unpaired
     * surrogate as `?`. Then none of the findings can be anchored, and the list is empty.
     */
    private fun findingsOf(written: LoadedSource, pending: PendingWrite): List<Diagnostic> =
        written.diagnostics.takeIf { written.text == pending.snapshot.text }.orEmpty()

    /**
     * What one save writes, which is [snapshot] in file form. [state] is the state of the undo history at [snapshot].
     */
    private class PendingWrite(val snapshot: TextSnapshot, val state: HistoryState, document: DefaultOpenDocument) {
        val withBom: Boolean = document.hasBom
        val content: String = LineSeparators.restore(snapshot.text, document.lineSeparator.value)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DocumentSaver::class.java)
    }
}
