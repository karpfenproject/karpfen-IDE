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
package io.karpfen.ide.core.document.antlr

import org.antlr.v4.runtime.Token

/**
 * Measures how deep the braces of a lexed text nest. The parsers need it for their nesting limit, which
 * [io.karpfen.ide.core.document.parse.NestingLimit] defines.
 *
 * Every opening brace token of the grammar goes one level deeper, and every closing one goes back up. The depth never
 * drops below zero, so a closing brace too many counts as nothing. String literals and `//` comments are tokens of
 * their own, so the braces inside them never count. A Python `#` comment in a KStates EVAL body is not a token,
 * though, so a `{` inside it is a brace token and counts.
 */
internal object NestingDepth {

    /**
     * The first opening brace among [tokens], lexed with [grammar], that opens a level deeper than [limit]. Returns
     * null if there is none.
     */
    fun firstBeyond(tokens: List<Token>, grammar: DslGrammar, limit: Int): Token? {
        var depth = 0
        for (token in tokens) {
            when (token.type) {
                grammar.openBrace -> if (++depth > limit) return token
                grammar.closeBrace -> depth = maxOf(0, depth - 1)
            }
        }
        return null
    }
}
