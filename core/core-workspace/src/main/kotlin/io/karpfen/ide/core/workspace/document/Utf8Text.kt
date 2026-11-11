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

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The byte form of the workspace's text files. It is the single encoding rule of [TextFileStore]. The encoding is
 * UTF-8. A byte order mark is written first on request, and it is removed and reported when reading. Reading decodes
 * strictly first. Only for invalid UTF-8 does it decode again with replacement characters. So a valid file that
 * happens to contain U+FFFD is not flagged. Writing encodes what UTF-8 cannot encode, an unpaired surrogate, as `?`.
 * The object has no state and no side effects.
 */
internal object Utf8Text {

    /** The bytes of [content], with a byte order mark first if [withBom]. */
    fun encode(content: String, withBom: Boolean): ByteArray {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return if (withBom) BOM + bytes else bytes
    }

    /** The text of [bytes]. A leading byte order mark is removed and reported. */
    fun decode(bytes: ByteArray): DecodedText {
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

    /** A decoder that fails on invalid UTF-8. */
    private fun strictDecoder() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /** A decoder that replaces invalid UTF-8 with U+FFFD. */
    private fun replacingDecoder() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** The UTF-8 byte order mark. */
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
