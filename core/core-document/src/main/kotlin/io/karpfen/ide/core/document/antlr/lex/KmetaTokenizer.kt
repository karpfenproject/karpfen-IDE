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

import io.karpfen.dsl.grammar.kmeta.KmetaLexer
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * [Tokenizer] for KMeta on the shared ANTLR mechanics of the `antlr` package: lexes with
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
        FailSafeTokenizer(KarpfenLanguage.KMETA, GapRecoveringTokenizer(DslGrammar.KMETA, KMETA_TOKEN_KINDS))

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = tokenizer.tokenize(snapshot)
}

/** The KMeta vocabulary: `type prop has knows list` are keywords; `WS` and `COMMENT` are skipped by the grammar. */
internal val KMETA_TOKEN_KINDS: TokenKindTable = TokenKindTable.of(
    TokenKind.KEYWORD to intArrayOf(KmetaLexer.TYPE, KmetaLexer.PROP, KmetaLexer.HAS, KmetaLexer.KNOWS, KmetaLexer.LIST),
    TokenKind.PUNCTUATION to
        intArrayOf(KmetaLexer.LBRACE, KmetaLexer.RBRACE, KmetaLexer.LPAREN, KmetaLexer.RPAREN, KmetaLexer.COMMA),
    TokenKind.STRING to intArrayOf(KmetaLexer.STRING),
)
