package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * [Tokenizer] for KStates (spec §3.1) on the shared ANTLR mechanics of the `antlr` package: lexes with
 * `KarpfenLexers.lex` (the KStates lexer has a catch-all `SYMBOL` token, so it reports no errors) and converts token
 * indices with `LexedText.rangeOf` (`CodePointOffsets`).
 *
 * - Comments are on the HIDDEN channel (`LINE_COMMENT`) and read from the full token list; whitespace is skipped.
 * - EVAL bodies and their content come from `EvalBodies.find`/`scan` (shared with the tree walker, so highlighting and
 *   references agree): CODE → EMBEDDED_CODE, KEYWORD → EMBEDDED_KEYWORD, STRING → EMBEDDED_STRING, NUMBER →
 *   EMBEDDED_NUMBER, COMMENT (`#`) → EMBEDDED_COMMENT, PATH_REFERENCE / MACRO_REFERENCE → the token kinds of the same
 *   name, KARPFEN_COMMENT (`//`) → COMMENT plus an `eval-double-slash` WARNING (source IDE).
 * - `EVENT` is an OPERATION inside action blocks and a KEYWORD inside CONDITION blocks (block structure tracked on the
 *   brace tokens).
 */
internal class KstatesTokenizer : Tokenizer {
    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = TODO("WP-TOKENS: tokenize KStates")
}
