package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * [Tokenizer] for KStates on top of the dsl-tools `kstates.KstatesLexer` with its console error listener removed and
 * an IDE collector added (spec §3.1).
 *
 * - Comments are on the HIDDEN channel and read from the full token stream.
 * - EVAL bodies (brace-balanced on the lexer's LBRACE/RBRACE tokens, exactly as the dsl-tools parser counts them) are
 *   re-scanned as embedded Python (`EMBEDDED_*`, `PATH_REFERENCE`, `MACRO_REFERENCE`).
 * - A `//` inside an EVAL body becomes a COMMENT token plus an `eval-double-slash` WARNING.
 * - `EVENT` is an OPERATION inside action blocks and a KEYWORD inside CONDITION blocks.
 *
 * Token offsets are converted with `CodePointOffsets`.
 */
internal class KstatesTokenizer : Tokenizer {
    override fun tokenize(snapshot: TextSnapshot): TokenizeResult = TODO("WP-TOKENS: tokenize KStates")
}
