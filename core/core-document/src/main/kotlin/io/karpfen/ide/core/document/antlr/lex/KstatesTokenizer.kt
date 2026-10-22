package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.EvalBodies
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot
import kstates.KstatesLexer
import org.antlr.v4.runtime.Token

/**
 * [Tokenizer] for KStates on the shared ANTLR mechanics of the `antlr` package: lexes with
 * `KarpfenLexers.lex` (the KStates lexer has a catch-all `SYMBOL` token, so it reports no errors) and converts token
 * indices with `LexedText.rangeOf` (`CodePointOffsets`).
 *
 * - Comments are on the HIDDEN channel (`LINE_COMMENT`) and read from the full token list; whitespace is skipped.
 * - EVAL bodies and their content come from `EvalBodies.find`/`scan` (shared with the tree walker, so highlighting and
 *   references agree): CODE → EMBEDDED_CODE, KEYWORD → EMBEDDED_KEYWORD, STRING → EMBEDDED_STRING, NUMBER →
 *   EMBEDDED_NUMBER, COMMENT (`#`) → EMBEDDED_COMMENT, PATH_REFERENCE / MACRO_REFERENCE → the token kinds of the same
 *   name, KARPFEN_COMMENT (`//`) → COMMENT plus an `eval-double-slash` WARNING (source IDE). The bodies' content
 *   ranges (`EvalBody.content`) are published as `TokenizeResult.embeddedRegions`, the one answer to "is this offset
 *   embedded Python?" for the editor and core-lang.
 * - `EVENT` is an OPERATION inside action blocks and a KEYWORD inside CONDITION blocks (block structure tracked on the
 *   brace tokens).
 *
 * Outside EVAL bodies the vocabulary is [KSTATES_TOKEN_KINDS]; a `SYMBOL` there (e.g. a stray `#` or `$`) is an ERROR
 * token without a diagnostic of its own: the lexer accepted it, and the parser reports it as a syntax error. The piece
 * mapping is [EmbeddedPieceTokens], the `EVENT` rule [KstatesBlockTracker]. Guarded by a [FailSafeTokenizer]; stateless
 * and thread-safe.
 */
internal class KstatesTokenizer : Tokenizer {

    private val tokenizer: Tokenizer = FailSafeTokenizer(KarpfenLanguage.KSTATES, Tokenizer(::tokenizeUnguarded))

    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = tokenizer.tokenize(snapshot)

    /**
     * Tokenizes [snapshot] without the guard. The tokens of an EVAL body are replaced by the body's pieces; its
     * closing brace is an ordinary token again.
     */
    private fun tokenizeUnguarded(snapshot: TextSnapshot): TokenizeResult {
        val text = snapshot.text
        val lexed = KarpfenLexers.lex(KarpfenLanguage.KSTATES, text)
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
 * The KStates vocabulary outside EVAL bodies: the operations of [KarpfenSyntax.KSTATES_OPERATIONS] (both cases are one
 * token type each, found by name in the lexer's vocabulary), every other grammar keyword (`EVENT` included; the
 * tokenizer turns it into an OPERATION in action blocks), double- and single-quoted strings, identifiers (e.g. `list`
 * in a type expression), numbers, `->`, punctuation and hidden `//` comments. `WS` is skipped; the catch-all `SYMBOL`
 * falls back to ERROR.
 */
internal val KSTATES_TOKEN_KINDS: TokenKindTable = TokenKindTable.of(
    TokenKind.OPERATION to KarpfenSyntax.KSTATES_OPERATIONS.map(::kstatesTokenType).toIntArray(),
    TokenKind.KEYWORD to intArrayOf(
        KstatesLexer.STATEMACHINE, KstatesLexer.ATTACHED, KstatesLexer.TO, KstatesLexer.STATES, KstatesLexer.STATE,
        KstatesLexer.INITIAL, KstatesLexer.ENTRY, KstatesLexer.DO, KstatesLexer.EVENT, KstatesLexer.WITH, KstatesLexer.AS,
        KstatesLexer.MACRO, KstatesLexer.EVAL, KstatesLexer.TRANSITIONS, KstatesLexer.TRANSITION, KstatesLexer.SPLIT,
        KstatesLexer.JOIN, KstatesLexer.NOT, KstatesLexer.LOOPING, KstatesLexer.CONDITION, KstatesLexer.VALUE,
        KstatesLexer.MACROS, KstatesLexer.TAKES, KstatesLexer.RETURNS, KstatesLexer.DEFINITION, KstatesLexer.REFERENCE,
        KstatesLexer.IF, KstatesLexer.IN, KstatesLexer.SCOPE,
    ),
    TokenKind.STRING to intArrayOf(KstatesLexer.STRING, KstatesLexer.SSTRING),
    TokenKind.IDENTIFIER to intArrayOf(KstatesLexer.IDENT),
    TokenKind.NUMBER to intArrayOf(KstatesLexer.NUMBER),
    TokenKind.OPERATOR to intArrayOf(KstatesLexer.ARROW),
    TokenKind.PUNCTUATION to intArrayOf(
        KstatesLexer.LBRACE, KstatesLexer.RBRACE, KstatesLexer.LPAREN, KstatesLexer.RPAREN, KstatesLexer.COMMA,
    ),
    TokenKind.COMMENT to intArrayOf(KstatesLexer.LINE_COMMENT),
)

/** The KStates token type whose symbolic name is [name]; throws if the lexer has none (a programming error). */
internal fun kstatesTokenType(name: String): Int =
    (1..KstatesLexer.VOCABULARY.maxTokenType).firstOrNull { KstatesLexer.VOCABULARY.getSymbolicName(it) == name }
        ?: error("the KStates lexer has no token $name")
