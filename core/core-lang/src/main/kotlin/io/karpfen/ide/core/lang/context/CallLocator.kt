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

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.text.TextRange

/**
 * Finds the innermost unclosed call before a token index. It scans backwards over balanced parentheses to an unmatched
 * `(` and counts the top-level commas. On the way it skips brace blocks that close before the index, such as an
 * `EVAL { ... }` argument. It stops at an unmatched `{`. The parents of the call are found the same way, starting from
 * the token before its name.
 */
internal object CallLocator {

    /** How many enclosing calls are looked up above the innermost one. */
    private const val MAX_PARENTS = 8

    /** The innermost unclosed call before the token at [endIndex] with its parents, or null outside every call. */
    fun locate(view: TokenView, endIndex: Int): CallContext? = locate(view, endIndex, MAX_PARENTS)

    /** [locate] with at most [parents] enclosing calls looked up. */
    private fun locate(view: TokenView, endIndex: Int, parents: Int): CallContext? {
        val tokens = view.tokens
        var depth = 0
        var i = minOf(endIndex, tokens.size) - 1
        while (i >= 0) {
            when (view.punctuationOf(tokens[i])) {
                ')' -> depth++
                '(' -> if (depth == 0) return callAt(view, i, endIndex, parents) else depth--
                '}' -> i = matchingOpenBrace(view, i) ?: return null
                '{' -> return null
            }
            i--
        }
        return null
    }

    /**
     * The call whose `(` is the token at [open]. Its name is the token before the `(`, with comments skipped. If that
     * token is punctuation, the call has no name. Its arguments are those that are complete before [endIndex].
     */
    private fun callAt(view: TokenView, open: Int, endIndex: Int, parents: Int): CallContext {
        val tokens = view.tokens
        var nameIndex = open - 1
        while (nameIndex >= 0 && tokens[nameIndex].kind == TokenKind.COMMENT) nameIndex--
        val nameToken = tokens.getOrNull(nameIndex)?.takeIf { view.punctuationOf(it) == null }
        val arguments = argumentsBetween(view, open + 1, minOf(endIndex, tokens.size))
        return CallContext(
            name = nameToken?.let(view::textOf).orEmpty(),
            nameRange = nameToken?.range ?: TextRange.empty(tokens[open].range.start),
            argumentIndex = arguments.size,
            arguments = arguments,
            parent = if (parents > 0 && nameToken != null) locate(view, nameIndex, parents - 1) else null,
        )
    }

    /**
     * The complete top-level arguments in the token range from [from] inclusive to [to] exclusive. Each entry is the
     * content of an argument that is a single string, or null for any other argument.
     */
    private fun argumentsBetween(view: TokenView, from: Int, to: Int): List<String?> {
        val arguments = ArrayList<String?>()
        val current = ArrayList<SyntaxToken>()
        var depth = 0
        for (i in from until to) {
            val token = view.tokens[i]
            if (token.kind == TokenKind.COMMENT) continue
            when (view.punctuationOf(token)) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) {
                    arguments += current.singleOrNull()?.takeIf { it.kind == TokenKind.STRING }?.let { view.contextToken(it).text }
                    current.clear()
                    continue
                }
            }
            current += token
        }
        return arguments
    }

    /** The index of the `{` that matches the `}` at [close], found by scanning backwards, or null if there is none. */
    private fun matchingOpenBrace(view: TokenView, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (view.punctuationOf(view.tokens[i])) {
                '}' -> depth++
                '{' -> if (--depth == 0) return i
            }
        }
        return null
    }
}
