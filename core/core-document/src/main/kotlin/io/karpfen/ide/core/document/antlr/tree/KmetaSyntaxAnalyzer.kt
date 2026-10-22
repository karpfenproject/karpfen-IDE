package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kmeta.KmetaParser
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KMeta with the generated dsl-tools [KmetaParser] (default error recovery) on a `CommonTokenStream` of
 * `KarpfenLexers.create`, with one `IdeErrorListener` attached to lexer and parser, and walks the tree into outline,
 * index, folding and semantic spans. Tolerates conjured "missing" tokens (negative indices) and error
 * nodes; token offsets are converted with `CodePointOffsets`. COMMENT folding (runs of ≥ 2 comment lines) uses the
 * comment pieces of `LexerGaps.scan`, the same ones the tokenizer turns into COMMENT tokens.
 *
 * Collaborators: [TreeParser] (parse), [KmetaTreeWalker] (outline, index, spans), [BlockFolding] and [CommentFolding].
 * Stateless and thread-safe.
 */
internal class KmetaSyntaxAnalyzer : SyntaxAnalyzer {
    override val language: KarpfenLanguage = KarpfenLanguage.KMETA

    private val parser = TreeParser(DslGrammar.KMETA, ::KmetaParser, KmetaParser::kmeta_file)

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis {
        val tree = parser.parse(snapshot)
        val collector = SyntaxCollector()
        val outline = KmetaTreeWalker(tree, collector).walk(tree.root)
        val folding = BlockFolding.of(tree, KmetaParser.LBRACE, KmetaParser.RBRACE) +
            CommentFolding.of(snapshot, GapComments.of(tree))
        return collector.toAnalysis(tree.diagnostics, outline, folding)
    }
}
