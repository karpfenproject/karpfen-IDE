package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KModel with the dsl-tools `kmodel.KmodelParser` (default error recovery) on a `CommonTokenStream` of
 * `KarpfenLexers.create`, with one `IdeErrorListener` attached to lexer and parser, and walks the tree into outline,
 * index, folding and semantic spans (spec §3.3). Tolerates conjured "missing" tokens (negative indices) and error
 * nodes; token offsets are converted with `CodePointOffsets`. COMMENT folding (runs of ≥ 2 comment lines) uses the
 * comment pieces of `LexerGaps.scan`, the same ones the tokenizer turns into COMMENT tokens.
 */
internal class KmodelSyntaxAnalyzer : SyntaxAnalyzer {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis = TODO("WP-PARSE: parse and walk KModel")
}
