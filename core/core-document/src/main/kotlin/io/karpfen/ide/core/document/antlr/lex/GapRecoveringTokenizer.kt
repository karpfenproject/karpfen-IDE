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

import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.antlr.LexerGaps
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * The tokenizing algorithm shared by KMeta and KModel, whose lexers skip whitespace and `//` comments. It has three
 * steps:
 *
 * 1. Lex with `KarpfenLexers.lex`, which attaches the IDE listener, so lexer errors become LEXER diagnostics. Classify
 *    every emitted token with [kinds] and take its range from `LexedText.rangeOf`.
 * 2. Recover what the lexer dropped with `LexerGaps.scan`. COMMENT pieces become [TokenKind.COMMENT] tokens and
 *    UNRECOGNIZED pieces become [TokenKind.ERROR] tokens. Their diagnostics come from the listener.
 * 3. Merge both by position.
 *
 * Every non-whitespace character ends up in exactly one token. By construction of the two grammars, the gaps contain
 * only whitespace, comments and unrecognized input.
 *
 * Step 2 has one refinement. A COMMENT piece that overlaps a LEXER error becomes an [TokenKind.ERROR] token. ANTLR's
 * error recovery consumes the character at which a token failed. In `ty//pe` the first `/` therefore ends the failed
 * `type`, and the lexer never sees a comment. The gap scanner only looks at characters and would report `//pe` as a
 * comment. With the refinement, every ERROR token overlaps a LEXER diagnostic and every LEXER diagnostic overlaps an
 * ERROR token, because lexer errors always become ERROR tokens plus ERROR diagnostics. The token invariant tests check
 * this on every mutation.
 *
 * The class holds no state and is thread-safe. The language tokenizers wrap it in a [FailSafeTokenizer].
 */
internal class GapRecoveringTokenizer(
    private val grammar: DslGrammar,
    private val kinds: TokenKindTable,
) : Tokenizer {

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult {
        val text = snapshot.text
        val lexed = KarpfenLexers.lex(grammar, text)
        val lexemes = lexed.tokens.mapNotNull { token -> lexed.rangeOf(token)?.let { range -> range to kinds.kindOf(token.type) } }
        val gaps = LexerGaps.scan(text, lexemes.map { (range, _) -> range })
        val lexerErrors = RangeOverlapIndex(lexed.diagnostics.map { it.range })

        val tokens = TokenSequence()
        var nextGap = 0
        fun addGap(gap: LexerGaps.GapPiece) = tokens.add(gap.range, kindOf(gap, lexerErrors))
        for ((range, kind) in lexemes) {
            while (nextGap < gaps.size && gaps[nextGap].range.start < range.start) addGap(gaps[nextGap++])
            tokens.add(range, kind)
        }
        gaps.subList(nextGap, gaps.size).forEach(::addGap)

        return TokenizeResult(snapshot.version, tokens.toList(), lexed.diagnostics)
    }

    /** The token kind of [gap]. It is ERROR for unrecognized input and for a comment that overlaps a lexer error. */
    private fun kindOf(gap: LexerGaps.GapPiece, lexerErrors: RangeOverlapIndex): TokenKind = when {
        gap.kind == LexerGaps.GapKind.UNRECOGNIZED -> TokenKind.ERROR
        lexerErrors.overlapsAny(gap.range) -> TokenKind.ERROR
        else -> TokenKind.COMMENT
    }
}
