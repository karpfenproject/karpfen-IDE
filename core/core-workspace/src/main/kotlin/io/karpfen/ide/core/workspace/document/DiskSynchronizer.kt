package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.session.ExternalDiagnosticSources
import io.karpfen.ide.core.text.EditOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Keeps open documents in line with their files: reacts to changes on disk and resolves conflicts, reporting every
 * outcome as a [ConflictResult] instead of throwing. Disk content is read through the workspace's [SourceLoader] on
 * [ioDispatcher]; documents are mutated through the [writer] only.
 *
 * Rules for a change of a document's file (compared with its disk state by content, [DefaultOpenDocument.knowsContentOf],
 * so a self-induced event of a save, or a touch without content change, is a no-op):
 * - content as known → nothing (a pending conflict is void and cleared);
 * - other content, clean document without conflict → reload as one undoable `External` change, save point marked, line
 *   separator and BOM adopted, FILE findings replaced;
 * - other content while dirty (or while a conflict is pending) → conflict MODIFIED;
 * - file gone → conflict DELETED (also for a clean document; the workbench offers Close / Keep mine);
 * - unreadable → logged, nothing changes.
 */
internal class DiskSynchronizer(
    private val sourceLoader: SourceLoader,
    private val ioDispatcher: CoroutineDispatcher,
    private val writer: SingleWriter,
) : ConflictResolver {

    /** The file of [document] changed on disk ([deleted]: it no longer exists). */
    suspend fun onFileChanged(document: DefaultOpenDocument, deleted: Boolean) {
        val disk = if (deleted) DiskContent.Absent else read(document.path)
        writer.run { follow(document, disk) }
    }

    override suspend fun resolve(document: DefaultOpenDocument, resolution: ConflictResolution): ConflictResult {
        if (document.conflict.value == null) return ConflictResult.NoConflict
        return when (resolution) {
            ConflictResolution.KEEP_MINE -> writer.run {
                document.keepMine()
                ConflictResult.Kept
            }
            ConflictResolution.RELOAD -> when (val disk = read(document.path)) {
                DiskContent.Absent -> ConflictResult.DeletedOnDisk
                is DiskContent.Unreadable -> ConflictResult.Failed(disk.message)
                is DiskContent.Present -> writer.run {
                    reload(document, disk.source)
                    ConflictResult.Reloaded
                }
            }
        }
    }

    /** Applies the rules of the class to [document] for the [disk] content just read. Writer only. */
    private fun follow(document: DefaultOpenDocument, disk: DiskContent) {
        when (disk) {
            is DiskContent.Unreadable -> logger.warn("Cannot read changed file {}: {}", document.path, disk.message)
            DiskContent.Absent -> if (document.diskState != null) document.raiseConflict(ExternalChangeKind.DELETED, null)
            is DiskContent.Present -> when {
                document.knowsContentOf(disk.source) -> document.clearConflict()
                document.conflict.value == null && !document.isDirtyNow -> reload(document, disk.source)
                else -> document.raiseConflict(ExternalChangeKind.MODIFIED, disk.source)
            }
        }
    }

    /** Takes the disk state [source]: one undoable `External` change, save point, separator, BOM and FILE findings. */
    private fun reload(document: DefaultOpenDocument, source: LoadedSource) {
        document.text.replaceAll(source.text, EditOrigin.External)
        document.text.undo.markSavePoint()
        document.synchronizedWith(source)
        document.session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, document.text.snapshot.value, source.diagnostics)
    }

    /**
     * Reads [path] through the loader; a file that cannot be read but no longer exists counts as absent, so a deletion
     * racing with the read is no read failure.
     */
    private suspend fun read(path: Path): DiskContent = withContext(ioDispatcher) {
        try {
            DiskContent.Present(sourceLoader.load(path))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: NoSuchFileException) {
            DiskContent.Absent
        } catch (e: Exception) {
            if (Files.exists(path)) DiskContent.Unreadable(describeIoFailure(path, e)) else DiskContent.Absent
        }
    }

    /** What a read of a document's file found. */
    private sealed interface DiskContent {
        /** The file exists and loaded as [source]. */
        data class Present(val source: LoadedSource) : DiskContent

        /** The file does not exist. */
        data object Absent : DiskContent

        /** The file exists but cannot be read; [message] is user-readable. */
        data class Unreadable(val message: String) : DiskContent
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DiskSynchronizer::class.java)
    }
}
