package io.karpfen.ide.core.workspace.document

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The byte form of the workspace's text files, the one encoding rule of [TextFileStore]: UTF-8, a byte order mark
 * written first on request and removed (and reported) when reading. Reading decodes strictly first and, only for invalid
 * UTF-8, again with replacement characters, so a valid file that happens to contain U+FFFD is not flagged; writing
 * encodes what UTF-8 cannot (an unpaired surrogate) as `?`. Pure.
 */
internal object Utf8Text {

    /** The bytes of [content], with a byte order mark first if [withBom]. */
    fun encode(content: String, withBom: Boolean): ByteArray {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return if (withBom) BOM + bytes else bytes
    }

    /** The text of [bytes]; a leading byte order mark is removed and reported. */
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

    private fun strictDecoder() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun replacingDecoder() = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** The UTF-8 byte order mark. */
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
