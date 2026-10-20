package io.karpfen.ide.core.workspace.document

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [SourceLoader]: the one loading rule (spec §1, Line separators and Encoding). */
class DefaultSourceLoaderTest {

    @TempDir
    lateinit var dir: Path

    private val loader = SourceLoader()

    private fun file(bytes: ByteArray): Path = dir.resolve("a.kmeta").also { Files.write(it, bytes) }

    private fun file(text: String): Path = file(text.toByteArray())

    @Test
    fun `LF files load unchanged and without findings`() {
        val path = file("a\nb\n")

        assertEquals(LoadedSource(path, "a\nb\n", LineSeparator.LF, hasBom = false, emptyList()), loader.load(path))
    }

    @Test
    fun `CRLF and CR files are normalized and remember their separator`() {
        val crlf = loader.load(file("a\r\nb\r\n"))
        assertEquals("a\nb\n", crlf.text)
        assertEquals(LineSeparator.CRLF, crlf.lineSeparator)
        assertEquals(emptyList(), crlf.diagnostics)

        val cr = loader.load(file("a\rb\r"))
        assertEquals("a\nb\n", cr.text)
        assertEquals(LineSeparator.CR, cr.lineSeparator)
        assertEquals(emptyList(), cr.diagnostics)
    }

    @Test
    fun `mixed separators are reported once, on the first minority line break of the normalized text`() {
        // Lines: "a" CRLF "bb" CRLF "c" LF "d" CR "e" CRLF; dominant CRLF, first minority the LF after "c".
        val loaded = loader.load(file("a\r\nbb\r\nc\nd\re\r\n"))

        assertEquals("a\nbb\nc\nd\ne\n", loaded.text)
        assertEquals(LineSeparator.CRLF, loaded.lineSeparator)
        val info = loaded.diagnostics.single()
        assertEquals(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, info.code)
        assertEquals(Severity.INFO, info.severity)
        assertEquals(DiagnosticSource.IDE, info.source)
        assertEquals(TextRange(6, 7), info.range)
        assertEquals("\n", loaded.text.substring(info.range.start, info.range.end))
    }

    @Test
    fun `malformed UTF-8 is a warning on the first replacement character`() {
        val loaded = loader.load(file(byteArrayOf('a'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), 0xFE.toByte())))

        assertEquals("a\n�b�", loaded.text)
        val warning = loaded.diagnostics.single()
        assertEquals(WorkspaceDiagnosticCodes.MALFORMED_ENCODING, warning.code)
        assertEquals(Severity.WARNING, warning.severity)
        assertEquals(TextRange(2, 3), warning.range)
    }

    @Test
    fun `a byte order mark is removed and remembered`() {
        val loaded = loader.load(file(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x\r\n".toByteArray()))

        assertEquals("x\n", loaded.text)
        assertTrue(loaded.hasBom)
        assertEquals(LineSeparator.CRLF, loaded.lineSeparator)
    }

    @Test
    fun `findings of a malformed file with mixed separators are sorted`() {
        val loaded = loader.load(file(byteArrayOf('a'.code.toByte(), '\n'.code.toByte(), 'b'.code.toByte(), '\n'.code.toByte(), 'c'.code.toByte(), '\r'.code.toByte(), 0xFF.toByte())))

        assertEquals(listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS, WorkspaceDiagnosticCodes.MALFORMED_ENCODING), loaded.diagnostics.map { it.code })
        assertEquals(listOf(5, 6), loaded.diagnostics.map { it.range.start })
    }
}
