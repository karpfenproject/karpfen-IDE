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

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange

/**
 * Describes a caret inside an EVAL body, which `TokenizeResult.embeddedRegionAt` finds. When the caret is inside a
 * PATH_REFERENCE token, after `$(` and before a closing `)`, it gives the path before the caret. When the caret is in a
 * MACRO_REFERENCE token or right after a lone `@`, it gives the macro name before the caret.
 */
internal object EmbeddedLocator {

    /** The context of [caret] inside the EVAL [body]. */
    fun locate(view: TokenView, tokens: TokenizeResult, body: TextRange, caret: Int): EmbeddedContext {
        val candidates = listOfNotNull(tokens.tokenAt(caret), tokens.tokenBefore(caret)).distinct()
        val path = candidates.firstOrNull { it.kind == TokenKind.PATH_REFERENCE && isInsidePath(view, it, caret) }
        val macro = candidates.firstOrNull { token ->
            (token.kind == TokenKind.MACRO_REFERENCE && caret > token.range.start && caret <= token.range.end) ||
                (token.kind == TokenKind.EMBEDDED_CODE && token.range.end == caret && view.textOf(token) == "@")
        }
        return EmbeddedContext(
            bodyRange = body,
            pathPrefix = path?.let { KarpfenSyntax.normalizeModelPath(view.text.substring(it.range.start + 2, caret)) },
            macroPrefix = macro?.let { view.text.substring(it.range.start + 1, caret) },
        )
    }

    /** True if [caret] lies after the `$(` of the path reference [token] and not after its closing `)`. */
    private fun isInsidePath(view: TokenView, token: SyntaxToken, caret: Int): Boolean {
        val closed = token.range.length > 2 && view.text.getOrNull(token.range.end - 1) == ')'
        val end = if (closed) token.range.end - 1 else token.range.end
        return caret >= token.range.start + 2 && caret <= end
    }
}
