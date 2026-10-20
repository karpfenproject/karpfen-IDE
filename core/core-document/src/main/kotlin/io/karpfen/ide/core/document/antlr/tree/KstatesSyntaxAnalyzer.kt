package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KStates with the dsl-tools `kstates.KstatesParser` (default error recovery) on a `CommonTokenStream` of
 * `KarpfenLexers.create`, with one `IdeErrorListener` attached to lexer and parser, and walks the tree into outline,
 * index, folding and semantic spans (spec §3.3). Tolerates conjured "missing" tokens (negative indices) and error
 * nodes; token offsets are converted with `CodePointOffsets`.
 *
 * Embedded Python is taken from the shared lexical scanner, not from the tree, so references agree with the tokenizer
 * on broken text too: `EvalBodies.find`/`scan` over the stream's tokens (every channel) yield MODEL_PATH references for
 * PATH_REFERENCE pieces (name = `EmbeddedPiece.name`, range = `EmbeddedPiece.inner`), MACRO references for
 * MACRO_REFERENCE pieces and EMBEDDED_CODE folding for multi-line bodies. COMMENT folding uses the hidden
 * `LINE_COMMENT` tokens.
 */
internal class KstatesSyntaxAnalyzer : SyntaxAnalyzer {
    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis = TODO("WP-PARSE: parse and walk KStates")
}
