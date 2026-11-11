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
package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * Collects the tokens of one tokenization in document order. The result always satisfies the invariants of
 * `TokenizeResult`: the tokens are sorted, do not overlap and are not empty. The tokenizer never throws while it builds
 * the result. The null ranges of conjured tokens and empty ranges are dropped. A range that starts before the end of
 * the previous token is clipped to that end.
 *
 * The tokenizers add sorted ranges without overlaps by construction. The token invariant tests prove this on every
 * example, mutation and prefix. The clipping only keeps a defect from turning into an exception in the editor. Use one
 * instance per tokenization. The class is not thread-safe.
 */
internal class TokenSequence {

    private val tokens = ArrayList<SyntaxToken>()
    private var end = 0

    /**
     * Appends a token of [kind] over [range]. The class documentation says what happens to null, empty and overlapping
     * ranges.
     */
    fun add(range: TextRange?, kind: TokenKind) {
        if (range == null) return
        val start = maxOf(range.start, end)
        if (start >= range.end) return
        tokens += SyntaxToken(if (start == range.start) range else TextRange(start, range.end), kind)
        end = range.end
    }

    /** The collected tokens, in document order. */
    fun toList(): List<SyntaxToken> = tokens.toList()
}
