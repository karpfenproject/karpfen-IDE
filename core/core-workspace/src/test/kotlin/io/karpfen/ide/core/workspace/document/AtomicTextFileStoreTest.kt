package io.karpfen.ide.core.workspace.document

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.AccessDeniedException
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
    fun `the temp file is forced to storage before it replaces the target, the directory after`() {
        val file = dir.resolve("a.kmeta")
        Files.writeString(file, "old")
        val forced = ArrayList<String>()
        val recording = FileChannelOpener { path, options ->
            object : ForwardingFileChannel(FileChannel.open(path, options)) {
                override fun force(metaData: Boolean) {
                    val what = when {
                        path == dir -> "directory"
                        path.parent == dir && path.fileName.toString().endsWith(".tmp") -> "temp file of ${size()} bytes"
                        else -> "unexpected $path"
                    }
                    forced += "$what, metadata $metaData, target '${Files.readString(file)}'"
                    super.force(metaData)
                }
            }
        }

        AtomicTextFileStore(recording).write(file, "new", withBom = true)

        assertEquals(
            listOf("temp file of 6 bytes, metadata true, target 'old'", "directory, metadata true, target '\uFEFFnew'"),
            forced,
            "the content is durable before the move, the move after it",
        )
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
    fun `a read-only file is never replaced`() {
        val file = dir.resolve("ro.kmeta")
        Files.writeString(file, "original")
        val readOnly = PosixFilePermissions.fromString("r--r--r--")
        Files.setPosixFilePermissions(file, readOnly)

        val failure = assertFailsWith<AccessDeniedException> { store.write(file, "overwritten") }

        assertEquals("the file is read-only", failure.reason)
        assertEquals("original", Files.readString(file))
        assertEquals(readOnly, Files.getPosixFilePermissions(file))
        assertFalse(dir.listDirectoryEntries().any { it.fileName.toString().endsWith(".tmp") }, "temp files: ${dir.listDirectoryEntries()}")
    }

    @Test
    fun `a read-only target behind a symbolic link is never replaced`() {
        val target = dir.resolve("real.kmeta")
        Files.writeString(target, "original")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
        val link = Files.createSymbolicLink(dir.resolve("link.kmeta"), target)

        assertFailsWith<AccessDeniedException> { store.write(link, "overwritten") }

        assertEquals("original", Files.readString(target))
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

/** A [FileChannel] that forwards everything to [delegate]; tests override what they observe. */
private open class ForwardingFileChannel(private val delegate: FileChannel) : FileChannel() {
    override fun read(dst: ByteBuffer): Int = delegate.read(dst)
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.read(dsts, offset, length)
    override fun read(dst: ByteBuffer, position: Long): Int = delegate.read(dst, position)
    override fun write(src: ByteBuffer): Int = delegate.write(src)
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.write(srcs, offset, length)
    override fun write(src: ByteBuffer, position: Long): Int = delegate.write(src, position)
    override fun position(): Long = delegate.position()
    override fun position(newPosition: Long): FileChannel = apply { delegate.position(newPosition) }
    override fun size(): Long = delegate.size()
    override fun truncate(size: Long): FileChannel = apply { delegate.truncate(size) }
    override fun force(metaData: Boolean) = delegate.force(metaData)
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = delegate.transferTo(position, count, target)
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = delegate.transferFrom(src, position, count)
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = delegate.map(mode, position, size)
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = delegate.lock(position, size, shared)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = delegate.tryLock(position, size, shared)
    override fun implCloseChannel() = delegate.close()
}
