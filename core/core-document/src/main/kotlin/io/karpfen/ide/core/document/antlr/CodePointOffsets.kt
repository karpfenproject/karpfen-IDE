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
package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.text.TextRange

/**
 * Converts ANTLR character indices to IDE offsets.
 *
 * `CharStreams.fromString` indexes the input by Unicode *code point*, while IDE offsets are UTF-16 code-unit indices
 * into the document `String`. The two agree until the first supplementary character, such as an emoji in a comment.
 * Every ANTLR adapter converts token indices through one instance per snapshot. That covers the tokenizers in
 * `antlr.lex` and the tree walkers in `antlr.tree`. Unpaired surrogates count as one code point, as in
 * [String.codePointAt] and in ANTLR's `CodePointBuffer`.
 */
internal class CodePointOffsets private constructor(
    private val textLength: Int,
    /** UTF-16 offset of every code point index, plus one entry for the end. Null when the mapping is the identity. */
    private val utf16ByCodePoint: IntArray?,
) {

    /** Number of code points, which is the size of the ANTLR char stream. */
    val codePointCount: Int get() = utf16ByCodePoint?.let { it.size - 1 } ?: textLength

    /**
     * UTF-16 offset of the code point at [codePointIndex]. `codePointCount` maps to the text length. Indices outside
     * `0..codePointCount` are clamped, because the "missing" tokens that ANTLR conjures carry index -1.
     */
    fun toUtf16(codePointIndex: Int): Int {
        val index = codePointIndex.coerceIn(0, codePointCount)
        return utf16ByCodePoint?.get(index) ?: index
    }

    /**
     * The IDE range of an ANTLR token or interval. [startIndex] and [stopIndex] are inclusive code point indices.
     * Returns null for conjured tokens, whose `startIndex < 0`. A stop before the start, as for EOF and for empty
     * intervals, yields an empty range.
     */
    fun range(startIndex: Int, stopIndex: Int): TextRange? {
        if (startIndex < 0) return null
        val start = toUtf16(startIndex)
        val end = toUtf16(maxOf(stopIndex + 1, startIndex))
        return TextRange(start, maxOf(start, end))
    }

    companion object {
        /**
         * The mapping for [text], built in O(length). When [text] has no surrogates, the mapping is the identity and
         * allocates no array.
         */
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
