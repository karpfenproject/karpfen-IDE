package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot
import kmodel.KmodelLexer

/**
 * [Tokenizer] for KModel on the shared ANTLR mechanics of the `antlr` package: lexes with
 * `KarpfenLexers.lex` (console listener removed, `IdeErrorListener` collecting LEXER diagnostics) and converts token
 * indices with `LexedText.rangeOf` (`CodePointOffsets`).
 *
 * The KModel lexer skips whitespace and comments: `LexerGaps.scan` recovers COMMENT pieces (→ COMMENT tokens) and
 * UNRECOGNIZED pieces (→ ERROR tokens; their diagnostics come from the listener) from the gaps between the tokens.
 *
 * The algorithm is the [GapRecoveringTokenizer] shared with KMeta, with the KModel vocabulary [KMODEL_TOKEN_KINDS],
 * guarded by a [FailSafeTokenizer]. Stateless and thread-safe.
 */
internal class KmodelTokenizer : Tokenizer {

    private val tokenizer: Tokenizer =
        FailSafeTokenizer(KarpfenLanguage.KMODEL, GapRecoveringTokenizer(DslGrammar.KMODEL, KMODEL_TOKEN_KINDS))

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = tokenizer.tokenize(snapshot)
}

/**
 * The KModel vocabulary: `make object prop has knows` are keywords, `->` an operator, `:` punctuation; double- and
 * single-quoted strings are one token type; `WS` and `COMMENT` are skipped by the grammar.
 */
internal val KMODEL_TOKEN_KINDS: TokenKindTable = TokenKindTable.of(
    TokenKind.KEYWORD to
        intArrayOf(KmodelLexer.MAKE, KmodelLexer.OBJECT, KmodelLexer.PROP, KmodelLexer.HAS, KmodelLexer.KNOWS),
    TokenKind.PUNCTUATION to
        intArrayOf(KmodelLexer.LBRACE, KmodelLexer.RBRACE, KmodelLexer.LPAREN, KmodelLexer.RPAREN, KmodelLexer.COLON),
    TokenKind.OPERATOR to intArrayOf(KmodelLexer.ARROW),
    TokenKind.STRING to intArrayOf(KmodelLexer.STRING),
)
