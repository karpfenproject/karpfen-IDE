package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.text.TextRange

/**
 * Converts ANTLR character indices to IDE offsets.
 *
 * `CharStreams.fromString` indexes the input by Unicode *code point*, while IDE offsets are UTF-16 code units
 * (spec §1). The two agree until the first supplementary character (e.g. an emoji in a comment). Every ANTLR adapter
 * (tokenizers in `antlr.lex`, tree walkers in `antlr.tree`) converts token indices through one instance per snapshot.
 * Unpaired surrogates count as one code point, as in [String.codePointAt] and ANTLR's `CodePointBuffer`.
 *
 * Shared by WP-TOKENS and WP-PARSE; complete in the contracts step.
 */
internal class CodePointOffsets private constructor(
    private val textLength: Int,
    /** UTF-16 offset of every code point index plus one entry for the end; null when the mapping is the identity. */
    private val utf16ByCodePoint: IntArray?,
) {

    /** Number of code points (the size of the ANTLR char stream). */
    val codePointCount: Int get() = utf16ByCodePoint?.let { it.size - 1 } ?: textLength

    /**
     * UTF-16 offset of the code point at [codePointIndex]; `codePointCount` maps to the text length. Indices outside
     * `0..codePointCount` are clamped (ANTLR's conjured "missing" tokens carry index -1).
     */
    fun toUtf16(codePointIndex: Int): Int {
        val index = codePointIndex.coerceIn(0, codePointCount)
        return utf16ByCodePoint?.get(index) ?: index
    }

    /**
     * The IDE range of an ANTLR token or interval with inclusive [startIndex] and [stopIndex] (code points); null for
     * conjured tokens (`startIndex < 0`). A stop before the start (EOF, empty intervals) yields an empty range.
     */
    fun range(startIndex: Int, stopIndex: Int): TextRange? {
        if (startIndex < 0) return null
        val start = toUtf16(startIndex)
        val end = toUtf16(maxOf(stopIndex + 1, startIndex))
        return TextRange(start, maxOf(start, end))
    }

    companion object {
        /** The mapping for [text]; O(length), identity (no allocation) when [text] has no surrogates. */
        fun of(text: String): CodePointOffsets {
            if (text.none { it.isSurrogate() }) return CodePointOffsets(text.length, null)
            val offsets = IntArray(text.codePointCount(0, text.length) + 1)
            var codePoint = 0
            var offset = 0
            while (offset < text.length) {
                offsets[codePoint++] = offset
                offset += Character.charCount(text.codePointAt(offset))
            }
            offsets[codePoint] = text.length
            return CodePointOffsets(text.length, offsets)
        }
    }
}
