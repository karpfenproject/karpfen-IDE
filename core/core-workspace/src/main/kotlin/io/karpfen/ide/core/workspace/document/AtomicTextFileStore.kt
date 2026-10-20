package io.karpfen.ide.core.workspace.document

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * [TextFileStore] on `java.nio.file`, writing through a temp file and an atomic move.
 *
 * Reading decodes strictly first and, only for invalid UTF-8, again with replacement characters, so a valid file that
 * happens to contain U+FFFD is not flagged. Writing:
 * - writes the bytes (byte order mark first if requested) to a new hidden temp file in the target's directory, created
 *   with the default permissions (a `createTempFile` would be owner-only);
 * - copies the POSIX permissions of an existing target to the temp file, so saving never changes them;
 * - writes through a symbolic link to its target instead of replacing the link;
 * - moves the temp file over the target (`ATOMIC_MOVE`, `REPLACE_EXISTING` where atomic moves are unsupported) and
 *   deletes the temp file if anything failed, leaving the target unchanged;
 * - creates missing parent directories (saving a document whose directory was deleted recreates it).
 */
internal class AtomicTextFileStore : TextFileStore {

    override fun read(path: Path): DecodedText {
        val bytes = Files.readAllBytes(path)
        val hasBom = bytes.size >= BOM.size && BOM.indices.all { bytes[it] == BOM[it] }
        val offset = if (hasBom) BOM.size else 0
        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        return try {
            DecodedText(strictDecoder().decode(buffer).toString(), malformed = false, hasBom = hasBom)
        } catch (e: CharacterCodingException) {
            buffer.position(offset)
            DecodedText(replacingDecoder().decode(buffer).toString(), malformed = true, hasBom = hasBom)
        }
    }

    override fun write(path: Path, content: String, withBom: Boolean) {
        val target = if (Files.isSymbolicLink(path)) path.toRealPath() else path.toAbsolutePath().normalize()
        val directory = checkNotNull(target.parent) { "cannot write the root directory $target" }
        Files.createDirectories(directory)
        val temp = directory.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            Files.write(temp, if (withBom) BOM + bytes else bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            copyPermissions(from = target, to = temp)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun copyPermissions(from: Path, to: Path) {
        if (!Files.exists(from)) return
        val source = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
        Files.getFileAttributeView(to, PosixFileAttributeView::class.java)?.setPermissions(source.readAttributes().permissions())
    }

    private fun strictDecoder() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun replacingDecoder() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private companion object {
        /** The UTF-8 byte order mark. */
        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
