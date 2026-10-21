package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.session.ExternalDiagnosticSources
import io.karpfen.ide.core.text.HistoryState
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Saves open documents (spec §4): the current snapshot is converted back to file form (the document's line separator,
 * its byte order mark) and written atomically through the [fileStore] on [ioDispatcher]; then, on the [writer]:
 * - the written state becomes the save point (an undo checkpoint taken with the snapshot): typing during the write
 *   keeps the document dirty, because the disk holds the older text, and undoing that typing makes it clean again;
 * - the written content becomes the document's disk state, so the watch event the write causes is recognized as
 *   self-induced, and a pending conflict is cleared (the disk now holds the buffer);
 * - the FILE findings are cleared for the written snapshot (the file is valid UTF-8 with one separator now).
 *
 * A failed write changes nothing and is reported as [SaveResult.Failed].
 */
internal class DocumentSaver(
    private val fileStore: TextFileStore,
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
        writer.run {
            document.text.undo.markSavePoint(pending.state)
            document.saved(pending.writtenState(document.path))
            document.session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, pending.snapshot, emptyList())
        }
        return SaveResult.Saved(document.path, pending.snapshot.version)
    }

    /** What one save writes: [snapshot] in file form; [state] is the undo history's state of [snapshot]. */
    private class PendingWrite(val snapshot: TextSnapshot, val state: HistoryState, document: DefaultOpenDocument) {
        val withBom: Boolean = document.hasBom
        val content: String = LineSeparators.restore(snapshot.text, document.lineSeparator.value)

        /** The disk state a later read of the written file yields (the loader's view of [content]). */
        fun writtenState(path: Path): LoadedSource {
            val normalized = LineSeparators.normalize(content)
            return LoadedSource(path, normalized.text, normalized.dominant, withBom, diagnostics = emptyList())
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DocumentSaver::class.java)
    }
}
