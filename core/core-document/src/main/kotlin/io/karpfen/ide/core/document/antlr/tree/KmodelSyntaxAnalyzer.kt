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
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kmodel.KmodelParser
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KModel with the generated dsl-tools [KmodelParser] (default error recovery) on a `CommonTokenStream` of
 * `KarpfenLexers.create`, with one `IdeErrorListener` attached to lexer and parser, and walks the tree into outline,
 * index, folding and semantic spans. Tolerates conjured "missing" tokens (negative indices) and error
 * nodes; token offsets are converted with `CodePointOffsets`. COMMENT folding (runs of ≥ 2 comment lines) uses the
 * comment pieces of `LexerGaps.scan`, the same ones the tokenizer turns into COMMENT tokens.
 *
 * Collaborators: [TreeParser] (parse), [KmodelTreeWalker] (outline, index, spans), [BlockFolding] and
 * [CommentFolding]. Stateless and thread-safe.
 */
internal class KmodelSyntaxAnalyzer : SyntaxAnalyzer {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL

    private val parser = TreeParser(DslGrammar.KMODEL, ::KmodelParser, KmodelParser::kmodel_file)

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis {
        val tree = parser.parse(snapshot)
        val collector = SyntaxCollector()
        val outline = KmodelTreeWalker(tree, collector).walk(tree.root)
        val folding = BlockFolding.of(tree, KmodelParser.LBRACE, KmodelParser.RBRACE) +
            CommentFolding.of(snapshot, GapComments.of(tree))
        return collector.toAnalysis(tree.diagnostics, outline, folding)
    }
}
