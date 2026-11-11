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

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * [TextFileStore] on `java.nio.file` that writes through a temp file and an atomic move. The bytes are those of
 * [Utf8Text], so `readBack` needs no override. Writing works like this.
 * - It writes the bytes to a new hidden temp file in the directory of the target, with a byte order mark first if
 *   requested. The temp file is created with the default permissions. A `createTempFile` would make it readable by the
 *   owner only.
 * - It copies the POSIX permissions of an existing target to the temp file, so saving never changes them.
 * - It forces the content and the metadata of the temp file to the storage device before the move. So a crash or a
 *   power loss can never leave a renamed but empty or truncated target. The atomic move protects the old content only
 *   if the new content is durable first.
 * - It writes through a symbolic link to its target instead of replacing the link.
 * - It refuses a target that exists but is not writable, such as a read-only file, with an `AccessDeniedException`
 *   whose reason is "the file is read-only". The move only needs write permission on the directory, so without this
 *   check it would silently replace a write-protected file.
 * - It moves the temp file over the target with `ATOMIC_MOVE`, or with `REPLACE_EXISTING` where atomic moves are not
 *   supported. If anything fails, it deletes the temp file and leaves the target unchanged.
 * - Then it forces the directory, so the move itself is durable. This only works where the platform can open a
 *   directory, which Windows cannot.
 * - It creates missing parent directories. So saving a document whose directory was deleted recreates the directory.
 *
 * @param channels opens the file channels it writes and forces through. Tests observe the forcing through this seam.
 */
internal class AtomicTextFileStore(
    private val channels: FileChannelOpener = FileChannelOpener { path, options -> FileChannel.open(path, options) },
) : TextFileStore {

    override fun read(path: Path): DecodedText = Utf8Text.decode(Files.readAllBytes(path))

    override fun write(path: Path, content: String, withBom: Boolean) {
        val target = if (Files.isSymbolicLink(path)) path.toRealPath() else path.toAbsolutePath().normalize()
        if (Files.exists(target) && !Files.isWritable(target)) {
            throw AccessDeniedException(target.toString(), null, READ_ONLY)
        }
        val directory = checkNotNull(target.parent) { "cannot write the root directory $target" }
        Files.createDirectories(directory)
        val temp = directory.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            val bytes = Utf8Text.encode(content, withBom)
            channels.open(temp, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).use { channel ->
                copyPermissions(from = target, to = temp)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        forceDirectory(directory)
    }

    /**
     * Makes the move durable. This is best effort, because not every platform can open a directory. A failure is only
     * logged, since the save itself succeeded.
     */
    private fun forceDirectory(directory: Path) {
        try {
            channels.open(directory, setOf(StandardOpenOption.READ)).use { it.force(true) }
        } catch (e: IOException) {
            logger.debug("Cannot force directory {} to storage: {}", directory, e.toString())
        }
    }

    /**
     * Gives [to] the POSIX permissions of an existing [from]. It does nothing if [from] does not exist or the file
     * system has no POSIX attributes.
     */
    private fun copyPermissions(from: Path, to: Path) {
        if (!Files.exists(from)) return
        val source = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
        Files.getFileAttributeView(to, PosixFileAttributeView::class.java)?.setPermissions(source.readAttributes().permissions())
    }

    private companion object {
        /** The reason of the failure to write a read-only file. */
        const val READ_ONLY: String = "the file is read-only"

        private val logger = LoggerFactory.getLogger(AtomicTextFileStore::class.java)
    }
}

/** Opens the [FileChannel]s of [AtomicTextFileStore]. In production this is `FileChannel.open`. */
internal fun interface FileChannelOpener {
    /** Opens [path] with [options]. */
    fun open(path: Path, options: Set<OpenOption>): FileChannel
}
