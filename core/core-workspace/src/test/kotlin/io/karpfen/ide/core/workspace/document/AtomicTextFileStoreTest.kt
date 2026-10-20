package io.karpfen.ide.core.workspace.document

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [TextFileStore]: UTF-8 with replacement, byte order marks, atomic writes. */
class AtomicTextFileStoreTest {

    @TempDir
    lateinit var dir: Path

    private val store = TextFileStore()
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test
    fun `valid UTF-8 is read unchanged, with its line separators`() {
        val file = dir.resolve("a.kmeta")
        Files.write(file, "type \"Ä\" \"€ 😀\" {\r\n}\r".toByteArray())

        assertEquals(DecodedText("type \"Ä\" \"€ 😀\" {\r\n}\r", malformed = false, hasBom = false), store.read(file))
    }

    @Test
    fun `a byte order mark is removed and flagged`() {
        val file = dir.resolve("a.kmeta")
        Files.write(file, bom + "x".toByteArray())

        assertEquals(DecodedText("x", malformed = false, hasBom = true), store.read(file))
    }

    @Test
    fun `malformed UTF-8 is replaced and flagged, a valid U+FFFD is not`() {
        val malformed = dir.resolve("bad.kmeta")
        Files.write(malformed, byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 'b'.code.toByte(), 0xFF.toByte()))
        val valid = dir.resolve("good.kmeta")
        Files.write(valid, "a�b".toByteArray())

        assertEquals(DecodedText("a�b�", malformed = true), store.read(malformed))
        assertEquals(DecodedText("a�b", malformed = false), store.read(valid))
    }

    @Test
    fun `reading a missing file throws an IOException`() {
        assertFailsWith<NoSuchFileException> { store.read(dir.resolve("missing.kmeta")) }
    }

    @Test
    fun `write then read round-trips the bytes, with and without byte order mark`() {
        val file = dir.resolve("a.kmeta")
        val content = "type \"A\" \"ä\" {\r\n    prop(\"x\", \"number\")\r\n}\r\n"

        store.write(file, content)
        assertContentEquals(content.toByteArray(), Files.readAllBytes(file))
        val read = store.read(file)
        store.write(file, read.content, read.hasBom)
        assertContentEquals(content.toByteArray(), Files.readAllBytes(file))

        store.write(file, content, withBom = true)
        assertContentEquals(bom + content.toByteArray(), Files.readAllBytes(file))
        assertEquals(DecodedText(content, malformed = false, hasBom = true), store.read(file))
    }

    @Test
    fun `writing replaces the file and leaves no temp file behind`() {
        val file = dir.resolve("a.kmeta")
        Files.writeString(file, "old content that is longer")

        store.write(file, "new")

        assertEquals("new", Files.readString(file))
        assertEquals(listOf(file), dir.listDirectoryEntries())
    }

    @Test
    fun `writing keeps the permissions of the existing file`() {
        val file = dir.resolve("a.kmeta")
        Files.writeString(file, "old")
        val permissions = PosixFilePermissions.fromString("rw-rw-r--")
        Files.setPosixFilePermissions(file, permissions)

        store.write(file, "new")

        assertEquals(permissions, Files.getPosixFilePermissions(file))
    }

    @Test
    fun `writing through a symbolic link updates its target and keeps the link`() {
        val target = dir.resolve("real.kmeta")
        Files.writeString(target, "old")
        val link = Files.createSymbolicLink(dir.resolve("link.kmeta"), target)

        store.write(link, "new")

        assertTrue(Files.isSymbolicLink(link))
        assertEquals("new", Files.readString(target))
    }

    @Test
    fun `writing into a missing directory creates it`() {
        val file = dir.resolve("gone/again/a.kmeta")

        store.write(file, "recreated")

        assertEquals("recreated", Files.readString(file))
    }

    @Test
    fun `a failed write leaves the target unchanged and no temp file`() {
        val blocked = dir.resolve("blocked.kmeta")
        Files.createDirectories(blocked)
        Files.writeString(blocked.resolve("child"), "kept")

        assertFailsWith<IOException> { store.write(blocked, "cannot replace a non-empty directory") }

        assertEquals("kept", Files.readString(blocked.resolve("child")))
        assertFalse(dir.listDirectoryEntries().any { it.fileName.toString().endsWith(".tmp") }, "temp files: ${dir.listDirectoryEntries()}")
    }
}
