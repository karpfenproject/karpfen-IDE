package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import io.karpfen.ide.core.workspace.settle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Opening, saving and closing documents (spec §4, `DocumentManager`). */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentManagerOpenSaveTest {

    @TempDir
    lateinit var dir: Path

    private val metamodel = "type \"Robot\" \"A robot\" {\n    prop(\"speed\", \"number\")\n}\n"

    private fun file(name: String, content: String): Path = dir.resolve(name).also { Files.writeString(it, content) }

    private fun bytes(name: String, content: ByteArray): Path = dir.resolve(name).also { Files.write(it, content) }

    @Test
    fun `opening returns one document per file, normalized, and the same one again`() = runTest {
        val manager = documentManager(this)
        val path = file("robot.kmeta", metamodel)

        val document = manager.openOrFail(dir.resolve("sub/../robot.kmeta"))

        assertEquals(path.toAbsolutePath().normalize(), document.path)
        assertEquals(FileKind.KMETA, document.fileKind)
        assertEquals(metamodel, document.currentText)
        assertSame(document, manager.openOrFail(path))
        assertSame(document, manager.get(dir.resolve("./robot.kmeta")))
        assertEquals(listOf(document), manager.openDocuments.value)
    }

    @Test
    fun `non-karpfen files are unsupported and unreadable files fail without exception`() = runTest {
        val manager = documentManager(this)
        val descriptor = file("robot.kenv.json", "{}")
        file("README.md", "readme")

        assertEquals(OpenResult.Unsupported(descriptor.toAbsolutePath().normalize()), manager.open(descriptor))
        assertIs<OpenResult.Unsupported>(manager.open(dir.resolve("README.md")))
        val failed = assertIs<OpenResult.Failed>(manager.open(dir.resolve("missing.kmeta")))
        assertTrue("missing.kmeta" in failed.message, failed.message)
        assertEquals(emptyList(), manager.openDocuments.value)
    }

    @Test
    fun `every session is syntax-only, also when the base configuration asks for standalone semantics`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val config = DocumentSessionConfig(semanticMode = SemanticMode.STANDALONE, tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher)
        val manager = documentManager(this, sessionConfig = config)
        // Converts with a dsl-tools error on its own: the relation target type does not exist.
        val path = file("broken.kmeta", "type \"Robot\" \"A robot\" {\n    has(\"arm\", \"Arm\")\n}\n")

        val document = manager.openOrFail(path)
        settle()

        val parse = checkNotNull(document.session.parse.value)
        assertNull(parse.standaloneModel)
        assertEquals(emptyList(), parse.standaloneDiagnostics)
        assertTrue(document.session.diagnostics.value.diagnostics.none { it.source == DiagnosticSource.DSL_TOOLS })
    }

    @Test
    fun `loading findings are the session's FILE diagnostics`() = runTest {
        val manager = documentManager(this)
        val path = bytes("mixed.kmeta", "type \"A\" \"a\" {\r\n}\r\n\n".toByteArray() + byteArrayOf(0xFF.toByte()))

        val document = manager.openOrFail(path)
        runCurrent()

        assertEquals(LineSeparator.CRLF, document.lineSeparator.value)
        assertEquals(
            listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, WorkspaceDiagnosticCodes.MALFORMED_ENCODING),
            document.diagnosticCodes.filter { it in listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, WorkspaceDiagnosticCodes.MALFORMED_ENCODING) },
        )
    }

    @Test
    fun `FILE findings last until a save or reload, also beyond the retained edit history`() = runTest {
        val manager = documentManager(this)
        val path = bytes("mixed.kmeta", "type \"A\" \"a\" {\r\n}\r\n\n".toByteArray() + byteArrayOf(0xFF.toByte()))
        val document = manager.openOrFail(path)
        runCurrent()
        val fileCodes = listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, WorkspaceDiagnosticCodes.MALFORMED_ENCODING)

        repeat(EditableText.DEFAULT_HISTORY_LIMIT + 1) {
            document.type(document.currentText.length, " ")
            runCurrent()
        }

        assertEquals(fileCodes, document.diagnosticCodes.filter { it in fileCodes })
    }

    @Test
    fun `the dirty flag follows the save point synchronously`() = runTest {
        val manager = documentManager(this)
        val document = manager.openOrFail(file("robot.kmeta", metamodel))
        assertFalse(document.isDirty.value)

        document.type(0, "// note\n")
        assertTrue(document.isDirty.value)

        document.session.text.undo.undo()
        assertFalse(document.isDirty.value)
    }

    @Test
    fun `documents use the workspace's time source for undo coalescing`() = runTest {
        val time = TestTimeSource()
        val manager = documentManager(this, timeSource = time)
        val document = manager.openOrFail(file("robot.kmeta", metamodel))

        document.type(0, "a")
        document.type(1, "b")
        time += 2000.milliseconds
        document.type(2, "c")

        document.session.text.undo.undo()
        assertEquals("ab$metamodel", document.currentText, "the pause on the injected clock started a new undo step")
        document.session.text.undo.undo()
        assertEquals(metamodel, document.currentText)
    }

    @Test
    fun `saving restores CRLF, CR and the byte order mark, and marks the save point`() = runTest {
        val manager = documentManager(this)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val crlf = file("crlf.kmeta", "type \"A\" \"a\" {\r\n}\r\n")
        val cr = file("cr.kmeta", "type \"A\" \"a\" {\r}\r")
        val withBom = bytes("bom.kmeta", bom + "type \"A\" \"a\" {\n}\n".toByteArray())

        for (path in listOf(crlf, cr, withBom)) {
            val document = manager.openOrFail(path)
            document.type(0, "// saved\n")
            val result = manager.save(path)
            assertEquals(SaveResult.Saved(document.path, document.session.text.snapshot.value.version), result)
            assertFalse(document.isDirty.value)
        }

        assertEquals("// saved\r\ntype \"A\" \"a\" {\r\n}\r\n", Files.readString(crlf))
        assertEquals("// saved\rtype \"A\" \"a\" {\r}\r", Files.readString(cr))
        assertContentEquals(bom + "// saved\ntype \"A\" \"a\" {\n}\n".toByteArray(), Files.readAllBytes(withBom))
    }

    @Test
    fun `an unchanged CRLF file round-trips byte for byte`() = runTest {
        val manager = documentManager(this)
        val content = "type \"A\" \"ä\" {\r\n    prop(\"x\", \"number\")\r\n}\r\n"
        val path = file("a.kmeta", content)
        manager.openOrFail(path)

        manager.save(path)

        assertContentEquals(content.toByteArray(), Files.readAllBytes(path))
    }

    @Test
    fun `a successful save clears the FILE findings and writes one separator`() = runTest {
        val manager = documentManager(this)
        val path = file("mixed.kmeta", "type \"A\" \"a\" {\r\n}\r\n\n")
        val document = manager.openOrFail(path)
        runCurrent()
        assertTrue(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS in document.diagnosticCodes)

        manager.save(path)
        runCurrent()

        assertTrue(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS !in document.diagnosticCodes)
        assertEquals("type \"A\" \"a\" {\r\n}\r\n\r\n", Files.readString(path))
    }

    @Test
    fun `a malformed UTF-8 file saves as valid UTF-8 with its replacement characters`() = runTest {
        val manager = documentManager(this)
        val path = bytes("bad.kmeta", "type \"A\" \"".toByteArray() + byteArrayOf(0xFF.toByte()) + "\" {\n}\n".toByteArray())
        val document = manager.openOrFail(path)
        runCurrent()
        assertTrue(WorkspaceDiagnosticCodes.MALFORMED_ENCODING in document.diagnosticCodes)

        manager.save(path)
        runCurrent()

        assertFalse(WorkspaceDiagnosticCodes.MALFORMED_ENCODING in document.diagnosticCodes)
        assertContentEquals("type \"A\" \"\uFFFD\" {\n}\n".toByteArray(Charsets.UTF_8), Files.readAllBytes(path))
        assertEquals(emptyList(), SourceLoader().load(path).diagnostics, "the file on disk is valid now")
    }

    @Test
    fun `a failed save keeps the document dirty and reports the failure`() = runTest {
        val manager = documentManager(this)
        val path = file("robot.kmeta", metamodel)
        val document = manager.openOrFail(path)
        document.type(0, "// change\n")
        Files.delete(path)
        Files.createDirectories(path.resolve("blocker"))

        val result = assertIs<SaveResult.Failed>(manager.save(path))

        assertTrue("robot.kmeta" in result.message, result.message)
        assertTrue(document.isDirty.value)
        assertIs<SaveResult.Failed>(manager.save(dir.resolve("not-open.kmeta")))
    }

    @Test
    fun `typing during a save makes the written text the save point`() = runTest {
        val disk = TextFileStore()
        var duringWrite: () -> Unit = {}
        val store = object : TextFileStore by disk {
            override fun write(path: Path, content: String, withBom: Boolean) {
                duringWrite()
                disk.write(path, content, withBom)
            }
        }
        val manager = documentManager(this, fileStore = store)
        val path = file("robot.kmeta", metamodel)
        val document = manager.openOrFail(path)
        document.type(0, "// v1\n")
        duringWrite = { document.type(0, "x") }

        assertIs<SaveResult.Saved>(manager.save(path))

        assertEquals("// v1\n$metamodel", Files.readString(path))
        assertTrue(document.isDirty.value, "the x typed during the write is not on disk")
        document.session.text.undo.undo()
        assertEquals("// v1\n$metamodel", document.currentText)
        assertFalse(document.isDirty.value, "back at the written text")
        document.session.text.undo.undo()
        assertEquals(metamodel, document.currentText)
        assertTrue(document.isDirty.value, "the disk holds the written text, not the opened one")
    }

    @Test
    fun `save all saves the dirty documents in opening order`() = runTest {
        val manager = documentManager(this)
        val first = manager.openOrFail(file("b.kmeta", metamodel))
        val clean = manager.openOrFail(file("c.kmeta", metamodel))
        val second = manager.openOrFail(file("a.kmeta", metamodel))
        second.type(0, "// 2\n")
        first.type(0, "// 1\n")

        val results = manager.saveAll()

        assertEquals(listOf(first.path, second.path), results.map { assertIs<SaveResult.Saved>(it).path })
        assertFalse(clean.isDirty.value)
        assertEquals("// 1\n$metamodel", Files.readString(first.path))
        assertEquals("// 2\n$metamodel", Files.readString(second.path))
    }

    @Test
    fun `closing removes the document and stops its session`() = runTest {
        val manager = documentManager(this)
        val path = file("robot.kmeta", metamodel)
        val document = manager.openOrFail(path)
        settle()
        val parsed = assertNotNull(document.session.parse.value)

        manager.close(path)
        document.type(0, "x")
        settle()

        assertNull(manager.get(path))
        assertEquals(emptyList(), manager.openDocuments.value)
        assertSame(parsed, document.session.parse.value, "a closed session no longer parses")
        manager.close(path) // no-op
    }

    @Test
    fun `close all discards every document`() = runTest {
        val manager = documentManager(this)
        manager.openOrFail(file("a.kmeta", metamodel)).type(0, "unsaved ")
        manager.openOrFail(file("b.kmeta", metamodel))

        manager.closeAll()

        assertEquals(emptyList(), manager.openDocuments.value)
        assertEquals(metamodel, Files.readString(dir.resolve("a.kmeta")))
    }
}
