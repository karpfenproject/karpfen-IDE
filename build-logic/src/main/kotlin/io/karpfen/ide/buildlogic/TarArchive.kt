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
package io.karpfen.ide.buildlogic

import java.io.OutputStream

/**
 * Writes a tar archive in the POSIX ustar format to [output], entry by entry, with the same owner and modification time
 * for every entry, so that the same entries always give the same bytes. It writes regular files only. Whoever unpacks
 * the archive creates the directories of their paths. [finish] writes the end of the archive and leaves [output] open.
 *
 * @param modificationTime the modification time of every entry, in seconds since the epoch.
 */
class TarArchive(private val output: OutputStream, private val modificationTime: Long) {

    /**
     * Appends the regular file [path] with [content] and the permission bits [mode], such as `"644".toInt(8)`.
     *
     * @throws IllegalArgumentException if [path] does not fit the name fields of a ustar header.
     */
    fun add(path: String, content: ByteArray, mode: Int) {
        output.write(header(path, content.size.toLong(), mode))
        output.write(content)
        val padding = (BLOCK - content.size % BLOCK) % BLOCK
        output.write(ByteArray(padding))
    }

    /** Writes the two empty blocks that end the archive. */
    fun finish() {
        output.write(ByteArray(2 * BLOCK))
    }

    /** The header block of the regular file [path] of [size] bytes with [mode]. */
    private fun header(path: String, size: Long, mode: Int): ByteArray {
        val (prefix, name) = split(path)
        val block = ByteArray(BLOCK)
        block.put(NAME, name.toByteArray(Charsets.UTF_8))
        block.put(MODE, octal(mode.toLong(), MODE.length))
        block.put(UID, octal(0, UID.length))
        block.put(GID, octal(0, GID.length))
        block.put(SIZE, octal(size, SIZE.length))
        block.put(MTIME, octal(modificationTime, MTIME.length))
        block.put(CHECKSUM, ByteArray(CHECKSUM.length) { ' '.code.toByte() })
        block[TYPE_FLAG] = REGULAR_FILE
        block.put(MAGIC, "ustar\u0000".toByteArray(Charsets.US_ASCII))
        block.put(VERSION, "00".toByteArray(Charsets.US_ASCII))
        block.put(OWNER_NAME, OWNER.toByteArray(Charsets.US_ASCII))
        block.put(GROUP_NAME, OWNER.toByteArray(Charsets.US_ASCII))
        block.put(DEVICE_MAJOR, octal(0, DEVICE_MAJOR.length))
        block.put(DEVICE_MINOR, octal(0, DEVICE_MINOR.length))
        block.put(PREFIX, prefix.toByteArray(Charsets.UTF_8))
        val checksum = block.sumOf { it.toInt() and 0xFF }
        block.put(CHECKSUM, "%06o".format(checksum).toByteArray(Charsets.US_ASCII) + byteArrayOf(0, ' '.code.toByte()))
        return block
    }

    /**
     * The prefix and name fields of [path]: the whole path as the name if it fits, else split at the last slash that
     * leaves both parts short enough.
     */
    private fun split(path: String): Pair<String, String> {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (bytes.size <= NAME.length) return "" to path
        val slash = path.indices.reversed().firstOrNull { index ->
            path[index] == '/' &&
                path.substring(0, index).toByteArray(Charsets.UTF_8).size <= PREFIX.length &&
                path.substring(index + 1).toByteArray(Charsets.UTF_8).size <= NAME.length
        }
        requireNotNull(slash) { "the path '$path' is too long for a ustar header" }
        return path.substring(0, slash) to path.substring(slash + 1)
    }

    /** [value] as zero-padded octal digits filling a field of [length] bytes, the last of them a NUL. */
    private fun octal(value: Long, length: Int): ByteArray {
        val digits = java.lang.Long.toOctalString(value).padStart(length - 1, '0')
        require(digits.length == length - 1) { "$value does not fit a ustar field of $length bytes" }
        return digits.toByteArray(Charsets.US_ASCII) + 0
    }

    /** Copies [bytes] into this block at the start of [field]. It fails if the field is too short. */
    private fun ByteArray.put(field: Field, bytes: ByteArray) {
        require(bytes.size <= field.length) { "a value of ${bytes.size} bytes does not fit a ustar field of ${field.length}" }
        bytes.copyInto(this, field.offset)
    }

    /**
     * A field of the ustar header.
     *
     * @property offset where it starts in the header block.
     * @property length how many bytes it has.
     */
    private class Field(val offset: Int, val length: Int)

    private companion object {
        /** The size of a header and of the blocks that file contents are padded to. */
        const val BLOCK = 512

        /** The type flag of a regular file. */
        const val REGULAR_FILE: Byte = '0'.code.toByte()

        /** The type flag's offset in the header. */
        const val TYPE_FLAG = 156

        /** The user and group name of every entry. */
        const val OWNER = "root"

        val NAME = Field(0, 100)
        val MODE = Field(100, 8)
        val UID = Field(108, 8)
        val GID = Field(116, 8)
        val SIZE = Field(124, 12)
        val MTIME = Field(136, 12)
        val CHECKSUM = Field(148, 8)
        val MAGIC = Field(257, 6)
        val VERSION = Field(263, 2)
        val OWNER_NAME = Field(265, 32)
        val GROUP_NAME = Field(297, 32)
        val DEVICE_MAJOR = Field(329, 8)
        val DEVICE_MINOR = Field(337, 8)
        val PREFIX = Field(345, 155)
    }
}
