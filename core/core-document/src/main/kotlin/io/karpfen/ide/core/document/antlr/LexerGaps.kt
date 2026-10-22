package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.text.TextRange

/**
 * Recovers what the KMeta and KModel lexers drop: both grammars `skip` whitespace (`[ \t\r\n]+`) and line
 * comments (`'//' ~[\r\n]*`), and a lexer error skips the characters it could not match. So the gaps between the
 * emitted tokens contain exactly whitespace, `//` comments and unrecognized characters.
 *
 * - The KMeta and KModel tokenizers turn [GapKind.COMMENT] pieces into COMMENT tokens and [GapKind.UNRECOGNIZED]
 *   pieces into ERROR tokens (the LEXER diagnostics come from [IdeErrorListener]).
 * - The syntax analyzers fold runs of comment lines (`FoldingKind.COMMENT`) from the same pieces.
 *
 * Not for KStates, whose lexer keeps comments on the HIDDEN channel and has a catch-all `SYMBOL` token. Pure.
 */
internal object LexerGaps {

    /** What a [GapPiece] is. */
    enum class GapKind {
        /** A `//` comment up to (excluding) the end of its line. */
        COMMENT,

        /** A run of characters the lexer could not match, up to the next whitespace, `//` or token. */
        UNRECOGNIZED,
    }

    /**
     * One non-whitespace piece of a gap.
     *
     * @property range the piece in the text.
     * @property kind what the piece is.
     */
    data class GapPiece(val range: TextRange, val kind: GapKind)

    /**
     * The pieces in the gaps of [text] between [tokenRanges] (the emitted tokens, sorted and non-overlapping, IDE
     * offsets), in document order. Whitespace (space, tab, CR, LF, as in the grammars) is not reported.
     */
    fun scan(text: String, tokenRanges: List<TextRange>): List<GapPiece> {
        val pieces = ArrayList<GapPiece>()
        var gapStart = 0
        for (token in tokenRanges) {
            scanGap(text, gapStart, minOf(token.start, text.length), pieces)
            gapStart = maxOf(gapStart, token.end)
        }
        scanGap(text, gapStart, text.length, pieces)
        return pieces
    }

    /** Appends the comments and unrecognized runs of the gap `[start, end)` to [into]. */
    private fun scanGap(text: String, start: Int, end: Int, into: MutableList<GapPiece>) {
        var i = start
        while (i < end) {
            when {
                isWhitespace(text[i]) -> i++
                isCommentStart(text, i, end) -> {
                    var j = i + 2
                    while (j < end && text[j] != '\n' && text[j] != '\r') j++
                    into += GapPiece(TextRange(i, j), GapKind.COMMENT)
                    i = j
                }
                else -> {
                    var j = i + 1
                    while (j < end && !isWhitespace(text[j]) && !isCommentStart(text, j, end)) j++
                    into += GapPiece(TextRange(i, j), GapKind.UNRECOGNIZED)
                    i = j
                }
            }
        }
    }

    /** Whitespace exactly as the KMeta and KModel grammars skip it. */
    private fun isWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\n'

    private fun isCommentStart(text: String, index: Int, end: Int): Boolean =
        index + 1 < end && text[index] == '/' && text[index + 1] == '/'
}
