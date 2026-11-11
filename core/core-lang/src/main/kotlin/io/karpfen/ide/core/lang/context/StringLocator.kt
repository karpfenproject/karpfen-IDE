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

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange

/**
 * Finds the string literal around a caret. This is either a STRING token the caret is strictly inside of, after the
 * opening quote and before the closing one, or an unterminated string. An unterminated string is the last ERROR token
 * on the line of the caret that starts with a quote, and it runs to the end of the line. Such ERROR tokens appear
 * because the KStates lexer emits a lone quote and the KMeta and KModel lexers drop the failed string, which the
 * tokenizer then reports as ERROR tokens.
 */
internal object StringLocator {

    /** The characters that open a string literal. */
    private const val QUOTES = "\"'"

    /** The string around [caret], or null if the caret is in none. */
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
