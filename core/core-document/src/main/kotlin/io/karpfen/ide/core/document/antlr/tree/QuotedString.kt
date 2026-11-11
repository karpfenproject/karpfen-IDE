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
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.text.TextRange

/**
 * A string literal of a karpfen grammar as names are read from it. Literals are double-quoted, and KModel also allows
 * single quotes. The literal holds the [value] between the quotes, its [inner] range and the [whole] literal. Escapes
 * are kept as written, as dsl-tools does when it unquotes.
 *
 * @property value the text between the quotes.
 * @property inner the range of [value]. It is empty for `""`.
 * @property whole the literal including its quotes.
 */
internal data class QuotedString(val value: String, val inner: TextRange, val whole: TextRange) {

    companion object {
        /**
         * The literal covering [whole] in [text]. A literal always starts and ends with its quote, because the grammars
         * require the closing quote. The inner range therefore drops one code unit on each side. A degenerate range
         * keeps an empty inner range at its start.
         */
        fun of(text: String, whole: TextRange): QuotedString {
            val inner = if (whole.length >= 2) TextRange(whole.start + 1, whole.end - 1) else TextRange.empty(whole.start)
            return QuotedString(text.substring(inner.start, inner.end), inner, whole)
        }
    }
}
