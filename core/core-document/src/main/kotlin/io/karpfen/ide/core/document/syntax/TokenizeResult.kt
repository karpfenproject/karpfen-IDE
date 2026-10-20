package io.karpfen.ide.core.document.syntax

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextRange

/**
 * Tokens and lexical diagnostics of one text version (spec §3.1).
 *
 * Invariants (checked): [tokens] are sorted by start and do not overlap; so are [embeddedRegions]. By contract of
 * [Tokenizer], every non-whitespace character belongs to exactly one token.
 *
 * @property version the [io.karpfen.ide.core.text.TextSnapshot.version] that was tokenized.
 * @property diagnostics LEXER errors and IDE warnings (e.g. `eval-double-slash`), sorted with [Diagnostic.ORDER].
 * @property embeddedRegions the embedded-Python regions: the content ranges of the KStates EVAL bodies (between the
 *   `{` after `EVAL` and its matching `}`, to the end of the text when unclosed), exactly as the tokenizer's EVAL
 *   scanner found them (`EvalBodies`, spec §3.1); empty for KMeta and KModel. The one answer to "is this offset embedded
 *   Python?" outside core-document (toggle comment `#` vs `//`, the caret context of completion): consumers use
 *   [embeddedRegionAt] and never re-derive EVAL bodies from the tokens (spec §1a, plan §5 rule 6).
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
     * The embedded region containing [offset] with inclusive bounds (`start <= offset <= end`, so a caret right after
     * `{` or right before `}` of an EVAL body is inside it), or null.
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

    /** The token containing [offset] (`start <= offset < end`), or null if [offset] is in whitespace or out of range. */
    public fun tokenAt(offset: Int): SyntaxToken? {
        val index = indexOfFirstEndingAfter(offset)
        val token = tokens.getOrNull(index) ?: return null
        return token.takeIf { offset in it.range }
    }

    /**
     * The token that ends exactly at [offset] or contains `offset - 1`, i.e. the token directly left of a caret at
     * [offset]; null if the character before [offset] is whitespace or [offset] is 0.
     */
    public fun tokenBefore(offset: Int): SyntaxToken? = if (offset <= 0) null else tokenAt(offset - 1)

    /** Tokens that overlap [range] (for an empty [range]: the token containing it), in document order. */
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

    /** Index of the first token whose end is greater than [offset] (binary search); `tokens.size` if none. */
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
        /** A result without tokens or diagnostics for [version] (e.g. the empty text). */
        public fun empty(version: Long): TokenizeResult = TokenizeResult(version, emptyList(), emptyList())
    }
}
