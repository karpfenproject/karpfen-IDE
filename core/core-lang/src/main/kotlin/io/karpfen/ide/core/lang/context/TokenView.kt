package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * The tokens of one text with the lookups the caret locators share: token texts, brackets, the statement before a
 * token and the word at an offset. Immutable; one instance per detection.
 *
 * Tokens may come from a broken text, so every lookup clamps to the text (a token never reaches past it when tokens
 * and text belong together, which the `LanguageServices` contract guarantees).
 */
internal class TokenView(val text: String, val tokens: List<SyntaxToken>) {

    /** The text of [token] (clamped to the text). */
    fun textOf(token: SyntaxToken): String =
        text.substring(token.range.start.coerceAtMost(text.length), token.range.end.coerceAtMost(text.length))

    /** [token] as a [ContextToken]: string literals without their quotes. */
    fun contextToken(token: SyntaxToken): ContextToken {
        val raw = textOf(token)
        val text = if (token.kind == TokenKind.STRING) unquote(raw) else raw
        return ContextToken(text, token.kind, token.range)
    }

    /** The bracket or separator character of a one-character PUNCTUATION token, or null for any other token. */
    fun punctuationOf(token: SyntaxToken): Char? =
        if (token.kind == TokenKind.PUNCTUATION && token.range.length == 1 && token.range.start < text.length) {
            text[token.range.start]
        } else {
            null
        }

    /** Index of the first token starting at or after [offset]; `tokens.size` if none. */
    fun firstIndexStartingAtOrAfter(offset: Int): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid].range.start < offset) low = mid + 1 else high = mid
        }
        return low
    }

    /** The identifier characters touching [offset] (empty at [offset] if there are none). */
    fun wordRange(offset: Int): TextRange {
        var start = offset
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && isWordChar(text[end])) end++
        return TextRange(start, end)
    }

    /**
     * The tokens of the construct that ends before the token at [index] (exclusive), in document order, comments and
     * embedded pieces excluded: everything after the previous brace; if an unclosed `(` comes first (the construct is
     * an argument of a call), everything after that call's last top-level comma or its `(`.
     */
    fun statementBefore(index: Int): List<ContextToken> {
        val collected = ArrayList<SyntaxToken>()
        var depth = 0
        var nearestTopLevelComma = -1
        var insideCall = false
        var i = minOf(index, tokens.size) - 1
        while (i >= 0 && collected.size < MAX_STATEMENT_TOKENS) {
            val token = tokens[i--]
            if (token.kind == TokenKind.COMMENT || token.kind.isEmbedded) continue
            when (punctuationOf(token)) {
                '{', '}' -> break
                ')' -> depth++
                '(' -> if (depth == 0) {
                    insideCall = true
                    break
                } else {
                    depth--
                }
                ',' -> if (depth == 0 && nearestTopLevelComma < 0) nearestTopLevelComma = collected.size
            }
            collected += token
        }
        val kept = if (insideCall && nearestTopLevelComma >= 0) collected.subList(0, nearestTopLevelComma) else collected
        return kept.asReversed().map(::contextToken)
    }

    companion object {
        /** Bound of [statementBefore] (constructs are short; broken text without braces must not cost more). */
        const val MAX_STATEMENT_TOKENS: Int = 64

        /** True for the characters of identifiers and path segments. */
        fun isWordChar(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'

        /** [raw] without its opening quote and, if present, its matching closing quote. */
        fun unquote(raw: String): String {
            if (raw.isEmpty() || (raw[0] != '"' && raw[0] != '\'')) return raw
            val closed = raw.length >= 2 && raw.last() == raw[0]
            return raw.substring(1, if (closed) raw.length - 1 else raw.length)
        }
    }
}
