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
package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.context.TokenView
import io.karpfen.ide.core.text.TextRange

/**
 * Reads the keys a KModel document writes from its tokens, which belong to the current text, so a key typed a moment
 * ago counts as well, even while the text has syntax errors. The templates of objects use it to give each new object a
 * free key and to leave out the relations an object has already. The scan is immutable and reads [document] only.
 */
internal class KmodelKeyScan(document: LanguageDocument) {

    private val text: String = document.text.text
    private val tokens: List<SyntaxToken> = document.tokens.tokens.filter { it.kind != TokenKind.COMMENT }

    /** The key of every `make object "key"` of the document. */
    fun objectKeys(): Set<String> =
        tokens.windowed(2).filter { (keyword, key) -> textOf(keyword) == OBJECT && key.kind == TokenKind.STRING }
            .map { (_, key) -> TokenView.unquote(textOf(key)) }
            .toSet()

    /**
     * The keys of the `has` statements directly in the body [block], which runs from an opening brace to its closing
     * brace or to the end of the text. Statements of the objects nested in the body do not count.
     */
    fun hasKeysIn(block: TextRange): Set<String> {
        val inside = tokens.filter { it.range.start > block.start && it.range.end <= block.end }
        val keys = HashSet<String>()
        var depth = 0
        inside.forEachIndexed { index, token ->
            when (textOf(token)) {
                "{" -> depth++
                "}" -> depth--
                HAS -> if (depth == 0) {
                    val key = inside.getOrNull(index + 2)
                    val opens = inside.getOrNull(index + 1)?.let(::textOf) == "("
                    if (opens && key?.kind == TokenKind.STRING) keys += TokenView.unquote(textOf(key))
                }
            }
        }
        return keys
    }

    /** The text of [token], clamped to the text. */
    private fun textOf(token: SyntaxToken): String =
        text.substring(token.range.start.coerceAtMost(text.length), token.range.end.coerceAtMost(text.length))

    /** The words the scan looks for. */
    private companion object {
        const val OBJECT = "object"
        const val HAS = "has"
    }
}
