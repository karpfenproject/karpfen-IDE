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
package io.karpfen.ide.runtime.host

/**
 * One file entry of a ustar archive as the tests read it.
 *
 * @property path the path, from the prefix and name fields.
 * @property mode the permission bits in octal digits, such as `644`.
 * @property content the file's bytes.
 */
internal class TarEntry(val path: String, val mode: String, val content: ByteArray) {
    /** The content as UTF-8 text. */
    val text: String get() = content.toString(Charsets.UTF_8)
}

/** Reads the file entries of uncompressed ustar archives for the tests, field by field. */
internal object TarEntries {
    /** The size of a tar block. */
    private const val BLOCK = 512

    /** The entries of the uncompressed ustar archive [bytes], up to its first empty block. */
    fun read(bytes: ByteArray): List<TarEntry> {
        val entries = ArrayList<TarEntry>()
        var offset = 0
        while (offset + BLOCK <= bytes.size && bytes.copyOfRange(offset, offset + BLOCK).any { it != 0.toByte() }) {
            val header = bytes.copyOfRange(offset, offset + BLOCK)
            val size = field(header, 124, 12).toInt(8)
            val prefix = field(header, 345, 155)
            val name = field(header, 0, 100)
            entries += TarEntry(
                path = if (prefix.isEmpty()) name else "$prefix/$name",
                mode = field(header, 100, 8).trimStart('0'),
                content = bytes.copyOfRange(offset + BLOCK, offset + BLOCK + size),
            )
            offset += BLOCK + (size + BLOCK - 1) / BLOCK * BLOCK
        }
        return entries
    }

    /** The text of the header field at [offset] of [length] bytes, up to its first NUL. */
    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000').trim()
}
