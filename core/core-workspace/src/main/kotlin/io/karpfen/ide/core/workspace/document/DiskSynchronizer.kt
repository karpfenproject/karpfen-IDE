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
 * Keeps open documents in line with their files. It reacts to changes on disk and resolves conflicts. It reports every
 * outcome as a [ConflictResult] instead of throwing. Disk content is read through the workspace's [SourceLoader] on
 * [ioDispatcher]. Documents are changed through the [writer] only.
 *
 * A change of a document's file is compared with the document's disk state by content, through
 * [DefaultOpenDocument.knowsContentOf]. So the event that a save causes itself, or a touch without a content change,
 * does nothing. These are the rules.
 * - If the content is as known, nothing happens. A pending conflict no longer applies and is cleared.
 * - If the content differs and the document is clean and has no conflict, the document is reloaded. The reload is one
 *   undoable `External` change. The save point is marked, the line separator and the byte order mark are adopted, and
 *   the FILE findings are replaced.
 * - If the content differs while the document is dirty, or while a conflict is pending, a MODIFIED conflict is raised.
 * - If the file is gone, a DELETED conflict is raised. This also happens for a clean document. The workbench then
 *   offers Close or Keep mine.
 * - If the file cannot be read, that is logged and nothing changes.
 */
internal class DiskSynchronizer(
    private val sourceLoader: SourceLoader,
    private val ioDispatcher: CoroutineDispatcher,
    private val writer: SingleWriter,
) : ConflictResolver {

    /** The file of [document] changed on disk. [deleted] is true if it no longer exists. */
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

    /**
     * Takes the disk state [source] into [document]. The text changes in one undoable `External` change. Then the save
     * point is marked, the line separator and the byte order mark are adopted, and the FILE findings are replaced.
     */
    private fun reload(document: DefaultOpenDocument, source: LoadedSource) {
        document.text.replaceAll(source.text, EditOrigin.External)
        document.text.undo.markSavePoint()
        document.synchronizedWith(source)
        document.session.setExternalDiagnostics(ExternalDiagnosticSources.FILE, document.text.snapshot.value, source.diagnostics)
    }

    /**
     * Reads [path] through the loader. A file that cannot be read but no longer exists counts as absent. So a deletion
     * that races with the read is not a read failure.
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

        /** The file exists but cannot be read. [message] is meant for the user. */
        data class Unreadable(val message: String) : DiskContent
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(DiskSynchronizer::class.java)
    }
}
