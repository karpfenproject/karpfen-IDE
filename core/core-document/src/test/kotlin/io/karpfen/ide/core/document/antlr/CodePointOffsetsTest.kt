package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.text.TextRange
import org.antlr.v4.runtime.CharStreams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [CodePointOffsets]: the identity without surrogates, the conversion of ANTLR's code-point indices to UTF-16
 * offsets after supplementary characters, and the clamping of conjured and end-of-file tokens.
 */
class CodePointOffsetsTest {

    @Test
    fun `is the identity for text without surrogates`() {
        val text = "type \"Vector\" \"ä€\" { }"
        val offsets = CodePointOffsets.of(text)

        assertEquals(text.length, offsets.codePointCount)
        assertEquals(7, offsets.toUtf16(7))
        assertEquals(TextRange(6, 12), offsets.range(6, 11))
    }

    /** ANTLR indexes by code point: the emoji is one index there and two UTF-16 units in the IDE. */
    @Test
    fun `maps ANTLR code point indices to UTF-16 offsets after supplementary characters`() {
        val text = "// 😀 smile\ntype"
        val offsets = CodePointOffsets.of(text)

        assertEquals(CharStreams.fromString(text).size(), offsets.codePointCount)
        val typeIndex = text.codePointCount(0, text.indexOf("type"))
        assertEquals(TextRange(text.indexOf("type"), text.length), offsets.range(typeIndex, typeIndex + 3))
        assertEquals(text.length, offsets.toUtf16(offsets.codePointCount))
    }

    @Test
    fun `clamps conjured and end-of-file tokens`() {
        val offsets = CodePointOffsets.of("ab\uD83D")

        assertNull(offsets.range(-1, -1))
        assertEquals(TextRange.empty(3), offsets.range(3, 2))
        assertEquals(0, offsets.toUtf16(-5))
        assertEquals(3, offsets.toUtf16(99))
    }
}
