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

import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.EvalBodies
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.Token

/**
 * [Tokenizer] for KStates on the shared ANTLR mechanics of the `antlr` package. It lexes with `KarpfenLexers.lex`. The
 * KStates lexer has a catch-all `SYMBOL` token, so it reports no errors. Token indices are converted with
 * `LexedText.rangeOf`, which uses `CodePointOffsets`.
 *
 * - Comments are `LINE_COMMENT` tokens on the HIDDEN channel and are read from the full token list. Whitespace is
 *   skipped.
 * - EVAL bodies and their content come from `EvalBodies.find` and `EvalBodies.scan`. The tree walker shares them, so
 *   highlighting and references agree. CODE becomes EMBEDDED_CODE, KEYWORD becomes EMBEDDED_KEYWORD, STRING becomes
 *   EMBEDDED_STRING, NUMBER becomes EMBEDDED_NUMBER, and a `#` COMMENT becomes EMBEDDED_COMMENT. PATH_REFERENCE and
 *   MACRO_REFERENCE become the token kinds of the same name. A `//` KARPFEN_COMMENT becomes a COMMENT plus an
 *   `eval-double-slash` WARNING with source IDE.
 * - The content ranges of the bodies, `EvalBody.content`, are published as `TokenizeResult.embeddedRegions`. They are
 *   the one answer to whether an offset lies in embedded Python, for the editor and for core-lang.
 * - `EVENT` is an OPERATION inside action blocks and a KEYWORD inside CONDITION blocks. The block structure is tracked
 *   on the brace tokens.
 *
 * Outside EVAL bodies the vocabulary is [KSTATES_TOKEN_KINDS]. A `SYMBOL` there, such as a stray `#` or `$`, is an
 * ERROR token without a diagnostic of its own, because the lexer accepted it and the parser reports it as a syntax
 * error. [EmbeddedPieceTokens] maps the pieces, and [KstatesBlockTracker] implements the `EVENT` rule. A
 * [FailSafeTokenizer] guards the tokenizer. The class holds no state and is thread-safe.
 */
internal class KstatesTokenizer : Tokenizer {

    private val tokenizer: Tokenizer = FailSafeTokenizer(KarpfenLanguage.KSTATES, Tokenizer(::tokenizeUnguarded))

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = tokenizer.tokenize(snapshot)

    /**
     * Tokenizes [snapshot] without the guard. The tokens of an EVAL body are replaced by the body's pieces. The closing
     * brace of the body is an ordinary token again.
     */
    private fun tokenizeUnguarded(snapshot: TextSnapshot): TokenizeResult {
        val text = snapshot.text
        val lexed = KarpfenLexers.lex(DslGrammar.KSTATES, text)
        val bodies = EvalBodies.find(lexed.tokens, lexed.offsets)
        val tokens = TokenSequence()
        val diagnostics = ArrayList<Diagnostic>(lexed.diagnostics)
        val blocks = KstatesBlockTracker()

        var nextBody = 0
        var index = 0
        while (index < lexed.tokens.size) {
            val body = bodies.getOrNull(nextBody)
            if (body != null && index >= body.firstToken) {
                for (piece in EvalBodies.scan(text, lexed.tokens, lexed.offsets, body)) {
                    tokens.add(piece.range, EmbeddedPieceTokens.kindOf(piece.kind))
                    EmbeddedPieceTokens.diagnosticOf(piece)?.let(diagnostics::add)
                }
                index = maxOf(index, body.endToken)
                nextBody++
                continue
            }
            val token = lexed.tokens[index++]
            if (token.type == KstatesLexer.WS) continue
            if (token.channel == Token.DEFAULT_CHANNEL) blocks.accept(token.type)
            val kind = if (token.type == KstatesLexer.EVENT) blocks.eventKind() else KSTATES_TOKEN_KINDS.kindOf(token.type)
            tokens.add(lexed.rangeOf(token), kind)
        }

        return TokenizeResult(
            version = snapshot.version,
            tokens = tokens.toList(),
            diagnostics = diagnostics.sortedWith(Diagnostic.ORDER),
            embeddedRegions = bodies.map { it.content },
        )
    }
}

/**
 * The KStates vocabulary outside EVAL bodies. It classifies these token types:
 * - the operations of [KarpfenSyntax.KSTATES_OPERATIONS]. The grammar accepts each operation in both cases with one
 *   token type, which is found by name in the lexer's vocabulary.
 * - every other grammar keyword, including `EVENT` and the words of histories, tick limits and breakpoints. The
 *   tokenizer turns `EVENT` into an OPERATION in action blocks.
 * - double-quoted and single-quoted strings.
 * - identifiers, such as `list` in a type expression.
 * - numbers, `->`, punctuation with the `:` of tick limits, and the hidden `//` comments.
 *
 * `WS` is skipped, and the catch-all `SYMBOL` falls back to ERROR.
 */
internal val KSTATES_TOKEN_KINDS: TokenKindTable = TokenKindTable.of(
    TokenKind.OPERATION to KarpfenSyntax.KSTATES_OPERATIONS.map(::kstatesTokenType).toIntArray(),
    TokenKind.KEYWORD to intArrayOf(
        KstatesLexer.STATEMACHINE, KstatesLexer.ATTACHED, KstatesLexer.TO, KstatesLexer.STATES, KstatesLexer.STATE,
        KstatesLexer.INITIAL, KstatesLexer.ENTRY, KstatesLexer.DO, KstatesLexer.EVENT, KstatesLexer.WITH, KstatesLexer.AS,
        KstatesLexer.MACRO, KstatesLexer.EVAL, KstatesLexer.TRANSITIONS, KstatesLexer.TRANSITION, KstatesLexer.SPLIT,
        KstatesLexer.JOIN, KstatesLexer.NOT, KstatesLexer.LOOPING, KstatesLexer.CONDITION, KstatesLexer.VALUE,
        KstatesLexer.MACROS, KstatesLexer.TAKES, KstatesLexer.RETURNS, KstatesLexer.DEFINITION, KstatesLexer.REFERENCE,
        KstatesLexer.IF, KstatesLexer.IN, KstatesLexer.SCOPE, KstatesLexer.SHALLOW, KstatesLexer.DEEP, KstatesLexer.HISTORY,
        KstatesLexer.BREAKPOINT, KstatesLexer.EXIT, KstatesLexer.START, KstatesLexer.END, KstatesLexer.TICK, KstatesLexer.LIMIT,
        KstatesLexer.DELAY, KstatesLexer.TIMEOUT, KstatesLexer.AFTER,
    ),
    TokenKind.STRING to intArrayOf(KstatesLexer.STRING, KstatesLexer.SSTRING),
    TokenKind.IDENTIFIER to intArrayOf(KstatesLexer.IDENT),
    TokenKind.NUMBER to intArrayOf(KstatesLexer.NUMBER),
    TokenKind.OPERATOR to intArrayOf(KstatesLexer.ARROW),
    TokenKind.PUNCTUATION to intArrayOf(
        KstatesLexer.LBRACE, KstatesLexer.RBRACE, KstatesLexer.LPAREN, KstatesLexer.RPAREN, KstatesLexer.COMMA,
        KstatesLexer.COLON,
    ),
    TokenKind.COMMENT to intArrayOf(KstatesLexer.LINE_COMMENT),
)

/** The KStates token type whose symbolic name is [name]. Throws if the lexer has none, which is a programming error. */
internal fun kstatesTokenType(name: String): Int =
    (1..KstatesLexer.VOCABULARY.maxTokenType).firstOrNull { KstatesLexer.VOCABULARY.getSymbolicName(it) == name }
        ?: error("the KStates lexer has no token $name")
