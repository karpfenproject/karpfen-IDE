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
package io.karpfen.ide.core.document.json

/**
 * Where the root value of a JSON token list starts, chosen so that one stray keystroke at the top of a text keeps the
 * tree of everything after it. The tokens before [index] stand before the root; [JsonTreeBuilder] skips and reports
 * them.
 *
 * The root starts at the first container, or at the first member of an object whose `{` is missing, as long as no
 * container comes before that member:
 * - tokens that start no value, such as a `}` that closes nothing, a separator or an INVALID token, never start it;
 * - a string, number or literal starts it only when no container and no member follows, as in a text that is one
 *   scalar. Before a container it is a stray value, such as a digit or a `true` typed in front of `{`;
 * - a string followed by `:` before any container is the first member of a root object without `{`. The brace was
 *   deleted, or a stray quote typed in front of it turned it into a string: before `{` a typed quote gets no closing
 *   partner, and the lexer ends the unterminated string `"{` at the line break.
 *
 * @property index the index of the root's first token.
 * @property isBraceless true if the root is an object without `{`, whose first member starts at [index].
 */
internal data class JsonRootStart(val index: Int, val isBraceless: Boolean) {

    /** Finds the [JsonRootStart] of a token list. */
    companion object {
        /** Where the root of [tokens] starts, or null if none of them starts a value. */
        fun of(tokens: List<JsonToken>): JsonRootStart? {
            val firstValue = tokens.indexOfFirst { it.kind.isValueStart }
            if (firstValue < 0) return null
            for (at in firstValue until tokens.size) {
                when {
                    tokens[at].kind.opensContainer -> return JsonRootStart(at, isBraceless = false)
                    tokens.startsMemberAt(at) -> return JsonRootStart(at, isBraceless = true)
                }
            }
            return JsonRootStart(firstValue, isBraceless = false)
        }
    }
}
