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
package io.karpfen.ide.core.lang.brackets

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * Matches `{}` and `()` over PUNCTUATION tokens (brackets inside strings, comments and embedded code are ignored),
 * preferring the bracket right of the offset, then the one left of it. Stateless.
 *
 * Pairing on broken text: brackets nest; a `)` pairs with an open `(` directly before it, a `}` with the nearest open
 * `{` (open parentheses inside the block stay unmatched: a call never spans a brace), anything else is unmatched and
 * has no match.
 */
internal class TokenBracketMatcher : BracketMatcher {

    override fun match(request: FeatureRequest, offset: Int): BracketMatch? {
        val document = request.document
        val text = document.text.text
        val tokens = document.tokens
        val candidates = listOfNotNull(tokens.tokenAt(offset), tokens.tokenBefore(offset)).distinct()
            .filter { bracketOf(text, it) != null }
        if (candidates.isEmpty()) return null
        val brackets = tokens.tokens.filter { bracketOf(text, it) != null }
        val partners = pair(text, brackets)
        for (candidate in candidates) {
            val index = brackets.binarySearchBy(candidate.range.start) { it.range.start }
            val partner = partners.getOrNull(index)?.takeIf { it >= 0 } ?: continue
            val (open, close) = if (index < partner) index to partner else partner to index
            return BracketMatch(brackets[open].range, brackets[close].range)
        }
        return null
    }

    /** The partner index of every bracket in [brackets] (-1 for an unmatched one). */
    private fun pair(text: String, brackets: List<SyntaxToken>): IntArray {
        val partners = IntArray(brackets.size) { -1 }
        val open = ArrayList<Int>()
        for ((index, token) in brackets.withIndex()) {
            when (bracketOf(text, token)) {
                '(', '{' -> open += index
                ')' -> if (open.isNotEmpty() && bracketOf(text, brackets[open.last()]) == '(') {
                    link(partners, open.removeAt(open.lastIndex), index)
                }
                '}' -> {
                    while (open.isNotEmpty() && bracketOf(text, brackets[open.last()]) == '(') open.removeAt(open.lastIndex)
                    if (open.isNotEmpty()) link(partners, open.removeAt(open.lastIndex), index)
                }
            }
        }
        return partners
    }

    /** Records the brackets at [open] and [close] as each other's partners. */
    private fun link(partners: IntArray, open: Int, close: Int) {
        partners[open] = close
        partners[close] = open
    }

    /** The bracket character of a one-character PUNCTUATION [token], or null for any other token. */
    private fun bracketOf(text: String, token: SyntaxToken): Char? {
        if (token.kind != TokenKind.PUNCTUATION || token.range.length != 1 || token.range.start >= text.length) return null
        return text[token.range.start].takeIf { it in BRACKETS }
    }

    private companion object {
        const val BRACKETS = "(){}"
    }
}
