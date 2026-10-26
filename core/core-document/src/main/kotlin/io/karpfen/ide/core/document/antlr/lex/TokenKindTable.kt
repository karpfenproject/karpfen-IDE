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

import io.karpfen.ide.core.document.syntax.TokenKind

/**
 * The [TokenKind] of each token type of one generated lexer, i.e. the language's vocabulary. Immutable; one constant
 * per language next to its tokenizer.
 *
 * Token types without an entry get [fallback] ([TokenKind.ERROR] unless stated otherwise): for the KStates lexer that
 * is its catch-all `SYMBOL` outside EVAL bodies. Types a tokenizer never classifies (skipped whitespace, KMeta/KModel
 * comments) simply have no entry; `TokenKindTableTest` checks that every other type of each vocabulary has one.
 */
internal class TokenKindTable private constructor(
    private val kinds: Map<Int, TokenKind>,
    private val fallback: TokenKind,
) {

    /** The kind of a token of [tokenType]. */
    fun kindOf(tokenType: Int): TokenKind = kinds[tokenType] ?: fallback

    /** True if [tokenType] has an entry of its own (not only the fallback). */
    fun classifies(tokenType: Int): Boolean = tokenType in kinds

    companion object {
        /**
         * A table from [groups] (a kind and the token types it applies to).
         * @throws IllegalArgumentException if a token type is listed twice (programming error).
         */
        fun of(vararg groups: Pair<TokenKind, IntArray>, fallback: TokenKind = TokenKind.ERROR): TokenKindTable {
            val kinds = HashMap<Int, TokenKind>()
            for ((kind, types) in groups) {
                for (type in types) {
                    val previous = kinds.put(type, kind)
                    require(previous == null) { "token type $type is classified as $previous and $kind" }
                }
            }
            return TokenKindTable(kinds, fallback)
        }
    }
}
