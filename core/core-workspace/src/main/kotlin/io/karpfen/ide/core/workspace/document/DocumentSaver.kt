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
 * Saves open documents: the current snapshot is converted back to file form (the document's line separator,
 * its byte order mark) and written atomically through the [fileStore] on [ioDispatcher]; then, on the [writer]:
 * - the written state becomes the save point (an undo checkpoint taken with the snapshot): typing during the write
 *   keeps the document dirty, because the disk holds the older text, and undoing that typing makes it clean again;
 * - the disk state the write leaves becomes the document's, so the watch event the write causes is recognized as
 *   self-induced, and a pending conflict is cleared (the disk now holds the buffer); the state comes from the
 *   [sourceLoader] (`SourceLoader.loadWritten`), the one loading rule, never from a rule of the saver's own;
 * - its findings become the FILE findings of the written snapshot (none for the default rule: the file is valid UTF-8
 *   with one separator now).
 *
 * A failed write changes nothing and is reported as [SaveResult.Failed].
 */
internal class DocumentSaver(
    private val fileStore: TextFileStore,
    private val sourceLoader: SourceLoader,
    private val ioDispatcher: CoroutineDispatcher,
    private val writer: SingleWriter,
) {

    /** Saves [document]; never throws (except for cancellation). */
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
     * text, which is the snapshot's unless the write changed it (an unpaired surrogate is written as `?`); then none of
     * them can be anchored and the list is empty.
     */
    private fun findingsOf(written: LoadedSource, pending: PendingWrite): List<Diagnostic> =
        written.diagnostics.takeIf { written.text == pending.snapshot.text }.orEmpty()

    /** What one save writes: [snapshot] in file form; [state] is the undo history's state of [snapshot]. */
    private class PendingWrite(val snapshot: TextSnapshot, val state: HistoryState, document: DefaultOpenDocument) {
        val withBom: Boolean = document.hasBom
        val content: String = LineSeparators.restore(snapshot.text, document.lineSeparator.value)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DocumentSaver::class.java)
    }
}
