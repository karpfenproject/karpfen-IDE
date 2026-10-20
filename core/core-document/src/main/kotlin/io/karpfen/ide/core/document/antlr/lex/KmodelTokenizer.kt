package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * [Tokenizer] for KModel (spec §3.1) on the shared ANTLR mechanics of the `antlr` package: lexes with
 * `KarpfenLexers.lex` (console listener removed, `IdeErrorListener` collecting LEXER diagnostics) and converts token
 * indices with `LexedText.rangeOf` (`CodePointOffsets`).
 *
 * The KModel lexer skips whitespace and comments: `LexerGaps.scan` recovers COMMENT pieces (→ COMMENT tokens) and
 * UNRECOGNIZED pieces (→ ERROR tokens; their diagnostics come from the listener) from the gaps between the tokens.
 */
internal class KmodelTokenizer : Tokenizer {
    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = TODO("WP-TOKENS: tokenize KModel")
}
