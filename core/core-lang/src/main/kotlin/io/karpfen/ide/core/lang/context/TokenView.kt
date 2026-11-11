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
package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * The tokens of one text with the lookups the caret locators share. These are token texts, brackets, the statement
 * before a token and the word at an offset. A view is immutable, and each detection makes its own.
 *
 * Tokens may come from a broken text, so every lookup clamps to the text. When tokens and text belong together, a
 * token never reaches past the text. The contract of `LanguageServices` guarantees that they do.
 */
internal class TokenView(val text: String, val tokens: List<SyntaxToken>) {

    /** The text of [token], clamped to the text. */
    fun textOf(token: SyntaxToken): String =
        text.substring(token.range.start.coerceAtMost(text.length), token.range.end.coerceAtMost(text.length))

    /** [token] as a [ContextToken]. String literals lose their quotes. */
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

    /** The index of the first token that starts at or after [offset], or `tokens.size` if there is none. */
    fun firstIndexStartingAtOrAfter(offset: Int): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid].range.start < offset) low = mid + 1 else high = mid
        }
        return low
    }

    /** The identifier characters that touch [offset]. If there are none, the range is empty at [offset]. */
    fun wordRange(offset: Int): TextRange {
        var start = offset
        while (start > 0 && KarpfenSyntax.isIdentifierChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && KarpfenSyntax.isIdentifierChar(text[end])) end++
        return TextRange(start, end)
    }

    /**
     * The tokens of the construct that ends before the token at [index], in document order. The token at [index] is
     * not included, and neither are comments and embedded pieces. The construct is everything after the previous
     * brace. If an unclosed `(` comes first, the construct is an argument of a call. Then it is everything after the
     * last top-level comma of that call, or after its `(`.
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
        /**
         * The most tokens [statementBefore] collects. Constructs are short, and broken text without braces must not
         * cost more.
         */
        const val MAX_STATEMENT_TOKENS: Int = 64

        /** [raw] without its opening quote and, if present, its matching closing quote. */
        fun unquote(raw: String): String {
            if (raw.isEmpty() || (raw[0] != '"' && raw[0] != '\'')) return raw
            val closed = raw.length >= 2 && raw.last() == raw[0]
            return raw.substring(1, if (closed) raw.length - 1 else raw.length)
        }
    }
}
