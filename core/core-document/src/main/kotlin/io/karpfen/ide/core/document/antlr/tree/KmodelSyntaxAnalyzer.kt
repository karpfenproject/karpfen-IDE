package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KModel with the dsl-tools `kmodel.KmodelParser` (default error recovery, IDE error listeners on lexer and parser)
 * and walks the tree into outline, index, folding and semantic spans (spec §3.3). Tolerates conjured "missing" tokens
 * (negative indices) and error nodes. Token offsets are converted with `CodePointOffsets`.
 */
internal class KmodelSyntaxAnalyzer : SyntaxAnalyzer {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis = TODO("WP-PARSE: parse and walk KModel")
}
