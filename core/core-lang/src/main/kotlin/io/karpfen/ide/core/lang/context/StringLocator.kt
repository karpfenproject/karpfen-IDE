package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange

/**
 * Finds the string literal around a caret: a STRING token the caret is strictly inside of (after the opening quote,
 * before the closing one), or an unterminated string — the last ERROR token on the caret's line that starts with a
 * quote (the KStates lexer emits a lone quote, the KMeta/KModel lexers drop the failed string, which the tokenizer
 * reports as ERROR tokens), running to the end of the line.
 */
internal object StringLocator {

    private const val QUOTES = "\"'"

    fun locate(view: TokenView, tokens: TokenizeResult, caret: Int): StringContext? {
        val before = tokens.tokenBefore(caret)
        if (before != null && before.kind == TokenKind.STRING && caret < before.range.end) {
            val raw = view.textOf(before)
            val closed = raw.length >= 2 && raw.last() == raw.first()
            val contentEnd = if (closed) before.range.end - 1 else before.range.end
            return StringContext(TextRange(before.range.start + 1, maxOf(before.range.start + 1, contentEnd)), closed)
        }
        val text = view.text
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val quote = tokens.tokensIn(TextRange(lineStart, caret)).lastOrNull { token ->
            token.kind == TokenKind.ERROR && token.range.start < caret && text[token.range.start] in QUOTES
        } ?: return null
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        return StringContext(TextRange(quote.range.start + 1, maxOf(quote.range.start + 1, lineEnd)), closed = false)
    }
}
