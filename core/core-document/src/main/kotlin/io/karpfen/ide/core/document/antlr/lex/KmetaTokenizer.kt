package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot
import kmeta.KmetaLexer

/**
 * [Tokenizer] for KMeta (spec §3.1) on the shared ANTLR mechanics of the `antlr` package: lexes with
 * `KarpfenLexers.lex` (console listener removed, `IdeErrorListener` collecting LEXER diagnostics) and converts token
 * indices with `LexedText.rangeOf` (`CodePointOffsets`).
 *
 * The KMeta lexer skips whitespace and comments: `LexerGaps.scan` recovers COMMENT pieces (→ COMMENT tokens) and
 * UNRECOGNIZED pieces (→ ERROR tokens; their diagnostics come from the listener) from the gaps between the tokens.
 *
 * The algorithm is the [GapRecoveringTokenizer] shared with KModel, with the KMeta vocabulary [KMETA_TOKEN_KINDS],
 * guarded by a [FailSafeTokenizer]. Stateless and thread-safe.
 */
internal class KmetaTokenizer : Tokenizer {

    private val tokenizer: Tokenizer =
        FailSafeTokenizer(KarpfenLanguage.KMETA, GapRecoveringTokenizer(KarpfenLanguage.KMETA, KMETA_TOKEN_KINDS))

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = tokenizer.tokenize(snapshot)
}

/** The KMeta vocabulary: `type prop has knows list` are keywords; `WS` and `COMMENT` are skipped by the grammar. */
internal val KMETA_TOKEN_KINDS: TokenKindTable = TokenKindTable.of(
    TokenKind.KEYWORD to intArrayOf(KmetaLexer.TYPE, KmetaLexer.PROP, KmetaLexer.HAS, KmetaLexer.KNOWS, KmetaLexer.LIST),
    TokenKind.PUNCTUATION to
        intArrayOf(KmetaLexer.LBRACE, KmetaLexer.RBRACE, KmetaLexer.LPAREN, KmetaLexer.RPAREN, KmetaLexer.COMMA),
    TokenKind.STRING to intArrayOf(KmetaLexer.STRING),
)
