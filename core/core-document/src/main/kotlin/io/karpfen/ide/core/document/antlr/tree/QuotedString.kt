package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.text.TextRange

/**
 * A string literal of a karpfen grammar (`"…"`, in KModel also `'…'`) as names are read from it (spec §3.3): the
 * [value] between the quotes, its [inner] range and the [whole] literal. Escapes are kept as written, as dsl-tools
 * does when it unquotes.
 *
 * @property value the text between the quotes.
 * @property inner the range of [value] (empty for `""`).
 * @property whole the literal including its quotes.
 */
internal data class QuotedString(val value: String, val inner: TextRange, val whole: TextRange) {

    companion object {
        /**
         * The literal covering [whole] in [text]. A literal always starts and ends with its quote (the grammars require
         * the closing quote), so the inner range drops one code unit on each side; a degenerate range keeps an empty
         * inner range at its start.
         */
        fun of(text: String, whole: TextRange): QuotedString {
            val inner = if (whole.length >= 2) TextRange(whole.start + 1, whole.end - 1) else TextRange.empty(whole.start)
            return QuotedString(text.substring(inner.start, inner.end), inner, whole)
        }
    }
}
