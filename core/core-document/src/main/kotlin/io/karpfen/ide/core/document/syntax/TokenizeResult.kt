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
package io.karpfen.ide.core.document.syntax

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextRange

/**
 * Tokens and lexical diagnostics of one text version.
 *
 * The constructor checks that [tokens] are sorted by start and do not overlap, and the same for [embeddedRegions].
 * Every [Tokenizer] also makes sure that every non-whitespace character belongs to exactly one token.
 *
 * @property version the [io.karpfen.ide.core.text.TextSnapshot.version] that was tokenized.
 * @property tokens the classified pieces of the text in document order.
 * @property diagnostics the LEXER errors, and the IDE error of a tokenizer that failed, sorted with [Diagnostic.ORDER].
 * @property embeddedRegions the regions of embedded Python. These are the content ranges of the KStates EVAL bodies,
 *   between the `{` after `EVAL` and its matching `}`, or to the end of the text when a body is not closed. They are
 *   exactly what the tokenizer's EVAL scanner, `EvalBodies`, found. The list is empty for KMeta and KModel. Outside
 *   core-document it is the one answer to whether an offset lies in embedded Python. Toggle comment uses it to choose
 *   between `#` and `//`, and completion uses it for the caret context. Consumers use [embeddedRegionAt] and never
 *   derive EVAL bodies from the tokens again, so a mechanism that several layers share is never implemented twice.
 */
public data class TokenizeResult(
    public val version: Long,
    public val tokens: List<SyntaxToken>,
    public val diagnostics: List<Diagnostic>,
    public val embeddedRegions: List<TextRange> = emptyList(),
) {
    init {
        for (i in 1 until tokens.size) {
            require(tokens[i - 1].range.end <= tokens[i].range.start) {
                "tokens must be sorted and non-overlapping: ${tokens[i - 1]} before ${tokens[i]}"
            }
        }
        for (i in 1 until embeddedRegions.size) {
            require(embeddedRegions[i - 1].end <= embeddedRegions[i].start) {
                "embedded regions must be sorted and non-overlapping: ${embeddedRegions[i - 1]} before ${embeddedRegions[i]}"
            }
        }
    }

    /**
     * The embedded region that contains [offset], or null. The bounds are inclusive, so `start <= offset <= end`. A
     * caret right after the `{` or right before the `}` of an EVAL body is therefore inside it.
     */
    public fun embeddedRegionAt(offset: Int): TextRange? {
        var low = 0
        var high = embeddedRegions.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (embeddedRegions[mid].end < offset) low = mid + 1 else high = mid
        }
        return embeddedRegions.getOrNull(low)?.takeIf { it.containsInclusive(offset) }
    }

    /**
     * The token that contains [offset], where `start <= offset < end`. Returns null if [offset] is in whitespace or out
     * of range.
     */
    public fun tokenAt(offset: Int): SyntaxToken? {
        val index = indexOfFirstEndingAfter(offset)
        val token = tokens.getOrNull(index) ?: return null
        return token.takeIf { offset in it.range }
    }

    /**
     * The token that ends exactly at [offset] or contains `offset - 1`. That is the token directly left of a caret at
     * [offset]. Returns null if the character before [offset] is whitespace or [offset] is 0.
     */
    public fun tokenBefore(offset: Int): SyntaxToken? = if (offset <= 0) null else tokenAt(offset - 1)

    /** The tokens that overlap [range], in document order. For an empty [range] it is the token that contains it. */
    public fun tokensIn(range: TextRange): List<SyntaxToken> {
        if (range.isEmpty) return listOfNotNull(tokenAt(range.start))
        val from = indexOfFirstEndingAfter(range.start)
        val result = ArrayList<SyntaxToken>()
        var i = from
        while (i < tokens.size && tokens[i].range.start < range.end) {
            result += tokens[i]
            i++
        }
        return result
    }

    /**
     * Index of the first token whose end is greater than [offset], found by binary search. Returns `tokens.size` if
     * there is none.
     */
    private fun indexOfFirstEndingAfter(offset: Int): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid].range.end <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    public companion object {
        /** A result without tokens or diagnostics for [version], as for the empty text. */
        public fun empty(version: Long): TokenizeResult = TokenizeResult(version, emptyList(), emptyList())
    }
}
