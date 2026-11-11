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

import io.karpfen.dsl.grammar.kmeta.KmetaParser
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KMeta with the generated dsl-tools [KmetaParser] and its default error recovery. The parser reads a
 * `CommonTokenStream` of `KarpfenLexers.create`, and one `IdeErrorListener` is attached to both lexer and parser. The
 * analyzer then walks the tree into outline, index, folding and semantic spans. It tolerates the "missing" tokens that
 * ANTLR conjures, which have negative indices, and error nodes. Token offsets are converted with `CodePointOffsets`.
 * COMMENT folding of runs of two or more comment lines uses the comment pieces of `LexerGaps.scan`, the same pieces
 * that the tokenizer turns into COMMENT tokens.
 *
 * [TreeParser] parses, [KmetaTreeWalker] builds outline, index and spans, and [BlockFolding] and [CommentFolding] fold.
 * The class holds no state and is thread-safe.
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
