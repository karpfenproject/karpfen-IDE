package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** External changes and the conflict protocol (spec §4, decision 43). */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalChangeTest {

    @TempDir
    lateinit var dir: Path

    private val original = "type \"Robot\" \"A robot\" {\n}\n"
    private val changed = "type \"Robot\" \"Changed on disk\" {\n}\n"

    private fun file(name: String = "robot.kmeta", content: String = original): Path =
        dir.resolve(name).also { Files.writeString(it, content) }

    @Test
    fun `a clean document reloads as one undoable External change`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        val origins = async { document.session.text.changes.first().origin }
        runCurrent()

        Files.writeString(path, changed.replace("\n", "\r\n"))
        manager.onFileChanged(path, deleted = false)

        assertEquals(changed, document.currentText)
        assertEquals(EditOrigin.External, origins.await())
        assertFalse(document.isDirty.value)
        assertNull(document.conflict.value)
        assertEquals(LineSeparator.CRLF, document.lineSeparator.value, "the separator follows the disk")

        document.session.text.undo.undo()
        assertEquals(original, document.currentText, "the reload is one undo step")
        assertTrue(document.isDirty.value)
    }

    @Test
    fun `a dirty document gets a MODIFIED conflict and keeps its text`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        document.type(0, "// mine\n")

        Files.writeString(path, changed)
        manager.onFileChanged(path, deleted = false)

        assertEquals(ExternalChange(document.path, ExternalChangeKind.MODIFIED), document.conflict.value)
        assertEquals("// mine\n$original", document.currentText)
    }

    @Test
    fun `RELOAD takes the disk state and KEEP_MINE keeps the buffer`() = runTest {
        val manager = documentManager(this)
        val reloaded = manager.openOrFail(file("a.kmeta"))
        val kept = manager.openOrFail(file("b.kmeta"))
        for (document in listOf(reloaded, kept)) {
            document.type(0, "// mine\n")
            Files.writeString(document.path, changed)
            manager.onFileChanged(document.path, deleted = false)
        }

        assertEquals(ConflictResult.Reloaded, reloaded.resolveConflict(ConflictResolution.RELOAD))
        assertEquals(changed, reloaded.currentText)
        assertFalse(reloaded.isDirty.value)
        assertNull(reloaded.conflict.value)

        assertEquals(ConflictResult.Kept, kept.resolveConflict(ConflictResolution.KEEP_MINE))
        assertEquals("// mine\n$original", kept.currentText)
        assertNull(kept.conflict.value)
        manager.onFileChanged(kept.path, deleted = false)
        assertNull(kept.conflict.value, "the disk state the user saw is not a new conflict")

        assertEquals(ConflictResult.NoConflict, kept.resolveConflict(ConflictResolution.RELOAD))
    }

    @Test
    fun `a deleted file is a DELETED conflict, RELOAD of it changes nothing, KEEP_MINE and save recreate it`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)

        Files.delete(path)
        manager.onFileChanged(path, deleted = true)

        assertEquals(ExternalChange(document.path, ExternalChangeKind.DELETED), document.conflict.value, "also for a clean document")
        val versionBefore = document.session.text.snapshot.value.version
        assertEquals(ConflictResult.DeletedOnDisk, document.resolveConflict(ConflictResolution.RELOAD))
        assertEquals(versionBefore, document.session.text.snapshot.value.version)
        assertEquals(original, document.currentText)
        assertEquals(ExternalChange(document.path, ExternalChangeKind.DELETED), document.conflict.value)

        assertEquals(ConflictResult.Kept, document.resolveConflict(ConflictResolution.KEEP_MINE))
        assertNull(document.conflict.value)
        manager.onFileChanged(path, deleted = true)
        assertNull(document.conflict.value, "the deletion was acknowledged")

        assertIs<SaveResult.Saved>(manager.save(path))
        assertEquals(original, Files.readString(path))
    }

    @Test
    fun `KEEP_MINE of a deleted file leaves the document dirty, so Save All recreates the file`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        Files.delete(path)
        manager.onFileChanged(path, deleted = true)

        assertEquals(ConflictResult.Kept, document.resolveConflict(ConflictResolution.KEEP_MINE))

        assertTrue(document.isDirty.value, "the kept buffer is the only copy of the file")
        assertEquals(listOf<SaveResult>(SaveResult.Saved(document.path, 0L)), manager.saveAll())
        assertEquals(original, Files.readString(path))
        assertFalse(document.isDirty.value)
    }

    @Test
    fun `KEEP_MINE of a modified file stays dirty when undone to the former save point`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        document.type(0, "// mine\n")
        Files.writeString(path, changed)
        manager.onFileChanged(path, deleted = false)

        document.resolveConflict(ConflictResolution.KEEP_MINE)
        document.session.text.undo.undo()

        assertEquals(original, document.currentText)
        assertTrue(document.isDirty.value, "the disk holds the external text, not the opened one")
    }

    @Test
    fun `after KEEP_MINE of a deleted file, a re-created file is a conflict, not a reload over the buffer`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        Files.delete(path)
        manager.onFileChanged(path, deleted = true)
        document.resolveConflict(ConflictResolution.KEEP_MINE)

        Files.writeString(path, changed)
        manager.onFileChanged(path, deleted = false)

        assertEquals(original, document.currentText)
        assertEquals(ExternalChange(document.path, ExternalChangeKind.MODIFIED), document.conflict.value)
    }

    @Test
    fun `a deletion reported as a change of the parent directory is detected by reading`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)

        Files.delete(path)
        manager.onFileChanged(path, deleted = false)

        assertEquals(ExternalChangeKind.DELETED, document.conflict.value?.kind)
    }

    /** Saves [path] while typing goes on, then reports the save's own watch event; the document must neither reload nor conflict. */
    private suspend fun TestScope.ownSaveWhileTyping(manager: DefaultDocumentManager, path: Path, typed: String) {
        val document = manager.openOrFail(path)
        document.type(0, typed)
        assertIs<SaveResult.Saved>(manager.save(path))
        document.type(document.currentText.length, "// typed after saving\n")
        val version = document.session.text.snapshot.value.version
        runCurrent()

        manager.onFileChanged(path, deleted = false)
        runCurrent()

        assertNull(document.conflict.value, "no conflict")
        assertEquals(version, document.session.text.snapshot.value.version, "no reload")
        assertTrue(document.isDirty.value)
    }

    @Test
    fun `the own save is recognized with a loader whose findings the saver cannot foresee`() = runTest {
        val extra = Diagnostic(TextRange.EMPTY, Severity.INFO, "audited", "audit", DiagnosticSource.IDE)
        val loader = SourceLoader { path -> SourceLoader().load(path).let { it.copy(diagnostics = it.diagnostics + extra) } }

        ownSaveWhileTyping(documentManager(this, sourceLoader = loader), file(), "// saved\n")
    }

    @Test
    fun `the own save is recognized when the text starts with a byte order mark character`() = runTest {
        ownSaveWhileTyping(documentManager(this), file(), "\uFEFF")
    }

    @Test
    fun `the watch event of the document's own save is ignored`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        document.type(0, "// saved\n")
        manager.save(path)
        document.type(0, "// typed after saving\n")
        val version = document.session.text.snapshot.value.version
        val conflicts = ArrayList<ExternalChange?>()
        backgroundScope.launch { document.conflict.collect { conflicts += it } }
        runCurrent()

        manager.onFileChanged(path, deleted = false)
        runCurrent()

        assertEquals(version, document.session.text.snapshot.value.version, "no reload")
        assertEquals(listOf<ExternalChange?>(null), conflicts, "no conflict")
        assertTrue(document.isDirty.value)
    }

    @Test
    fun `a file restored to the known content voids a pending conflict`() = runTest {
        val manager = documentManager(this)
        val path = file()
        val document = manager.openOrFail(path)
        document.type(0, "// mine\n")
        Files.writeString(path, changed)
        manager.onFileChanged(path, deleted = false)
        assertEquals(ExternalChangeKind.MODIFIED, document.conflict.value?.kind)

        Files.writeString(path, original)
        manager.onFileChanged(path, deleted = false)

        assertNull(document.conflict.value)
    }

    @Test
    fun `every reload replaces the FILE findings`() = runTest {
        val manager = documentManager(this)
        val path = file(content = "type \"A\" \"a\" {\r\n}\n")
        val document = manager.openOrFail(path)
        runCurrent()
        assertTrue(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS in document.diagnosticCodes)

        Files.write(path, "type \"A\" \"b\" {\n}\n".toByteArray() + byteArrayOf(0xFF.toByte()))
        manager.onFileChanged(path, deleted = false)
        runCurrent()

        val codes = document.diagnosticCodes
        assertTrue(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS !in codes, "$codes")
        assertTrue(WorkspaceDiagnosticCodes.MALFORMED_ENCODING in codes, "$codes")

        Files.writeString(path, original)
        manager.onFileChanged(path, deleted = false)
        runCurrent()
        val fileCodes = listOf(WorkspaceDiagnosticCodes.MALFORMED_ENCODING, WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS)
        assertTrue(document.diagnosticCodes.none { it in fileCodes }, "a clean reload clears them")
    }

    @Test
    fun `a failed read during RELOAD keeps buffer and conflict`() = runTest {
        var failing = false
        val loader = SourceLoader { path -> if (failing) throw IOException("disk on fire") else SourceLoader().load(path) }
        val manager = documentManager(this, sourceLoader = loader)
        val path = file()
        val document = manager.openOrFail(path)
        document.type(0, "// mine\n")
        Files.writeString(path, changed)
        manager.onFileChanged(path, deleted = false)

        failing = true
        val result = assertIs<ConflictResult.Failed>(document.resolveConflict(ConflictResolution.RELOAD))

        assertTrue("disk on fire" in result.message, result.message)
        assertEquals("// mine\n$original", document.currentText)
        assertEquals(ExternalChangeKind.MODIFIED, document.conflict.value?.kind)
        failing = false
        manager.onFileChanged(path, deleted = false)
        assertEquals(ExternalChangeKind.MODIFIED, document.conflict.value?.kind)
    }

    @Test
    fun `an unreadable change is ignored`() = runTest {
        var failing = false
        val loader = SourceLoader { path -> if (failing) throw IOException("busy") else SourceLoader().load(path) }
        val manager = documentManager(this, sourceLoader = loader)
        val path = file()
        val document = manager.openOrFail(path)

        failing = true
        manager.onFileChanged(path, deleted = false)

        assertNull(document.conflict.value)
        assertEquals(original, document.currentText)
    }

    @Test
    fun `changes of files that are not open are ignored`() = runTest {
        val manager = documentManager(this)
        launch { manager.onFileChanged(file("other.kmeta"), deleted = false) }.join()
        assertEquals(emptyList(), manager.openDocuments.value)
    }
}
