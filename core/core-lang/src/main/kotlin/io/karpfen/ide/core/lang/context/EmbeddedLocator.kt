package io.karpfen.ide.core.lang.context

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextRange

/**
 * Describes a caret inside an EVAL body (found with `TokenizeResult.embeddedRegionAt`): the path before the caret when
 * it is inside a PATH_REFERENCE token (after `$(`, before a closing `)`), the macro name before the caret when it is in a
 * MACRO_REFERENCE token or right after a lone `@`.
 */
internal object EmbeddedLocator {

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

    private fun isInsidePath(view: TokenView, token: SyntaxToken, caret: Int): Boolean {
        val closed = token.range.length > 2 && view.text.getOrNull(token.range.end - 1) == ')'
        val end = if (closed) token.range.end - 1 else token.range.end
        return caret >= token.range.start + 2 && caret <= end
    }
}
