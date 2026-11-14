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
 * [Tokenizer] for KMeta on the shared ANTLR mechanics of the `antlr` package. It lexes with `KarpfenLexers.lex`, which
 * removes the console listener and lets `IdeErrorListener` collect LEXER diagnostics. It converts token indices with
 * `LexedText.rangeOf`, which uses `CodePointOffsets`.
 *
 * The KMeta lexer skips whitespace and comments. `LexerGaps.scan` recovers them from the gaps between the tokens.
 * COMMENT pieces become COMMENT tokens, and UNRECOGNIZED pieces become ERROR tokens. The diagnostics of the ERROR
 * tokens come from the listener.
 *
 * The algorithm is the [GapRecoveringTokenizer] shared with KModel, with the KMeta vocabulary [KMETA_TOKEN_KINDS] and
 * guarded by a [FailSafeTokenizer]. The class holds no state and is thread-safe.
 */
internal class KmetaTokenizer : Tokenizer {

    private val tokenizer: Tokenizer =
        FailSafeTokenizer(KarpfenLanguage.KMETA, GapRecoveringTokenizer(DslGrammar.KMETA, KMETA_TOKEN_KINDS))

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = tokenizer.tokenize(snapshot)
}

/**
 * The KMeta vocabulary. `type`, `prop`, `has`, `knows`, `list` and the two words of the clause `behaves after` are
 * keywords. The grammar skips `WS` and `COMMENT`, so they have no entry.
 */
internal val KMETA_TOKEN_KINDS: TokenKindTable = TokenKindTable.of(
    TokenKind.KEYWORD to intArrayOf(
        KmetaLexer.TYPE, KmetaLexer.PROP, KmetaLexer.HAS, KmetaLexer.KNOWS, KmetaLexer.LIST, KmetaLexer.BEHAVES,
        KmetaLexer.AFTER,
    ),
    TokenKind.PUNCTUATION to
        intArrayOf(KmetaLexer.LBRACE, KmetaLexer.RBRACE, KmetaLexer.LPAREN, KmetaLexer.RPAREN, KmetaLexer.COMMA),
    TokenKind.STRING to intArrayOf(KmetaLexer.STRING),
)
