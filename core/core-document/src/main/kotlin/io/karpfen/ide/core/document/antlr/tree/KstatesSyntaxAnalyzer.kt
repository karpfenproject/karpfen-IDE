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

import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.dsl.grammar.kstates.KstatesParser
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses KStates with the generated dsl-tools [KstatesParser] and its default error recovery. The parser reads a
 * `CommonTokenStream` of `KarpfenLexers.create`, and one `IdeErrorListener` is attached to both lexer and parser. The
 * analyzer then walks the tree into outline, index, folding and semantic spans. It tolerates the "missing" tokens that
 * ANTLR conjures, which have negative indices, and error nodes. Token offsets are converted with `CodePointOffsets`.
 *
 * Embedded Python is taken from the shared lexical scanner and not from the tree, so references agree with the
 * tokenizer on broken text too. `EvalBodies.find` and `EvalBodies.scan` run over the tokens of every channel in the
 * stream. PATH_REFERENCE pieces yield MODEL_PATH references, named by `EmbeddedPiece.name` at the range
 * `EmbeddedPiece.inner`. MACRO_REFERENCE pieces yield MACRO references, and multi-line bodies yield EMBEDDED_CODE
 * folding. COMMENT folding uses the hidden `LINE_COMMENT` tokens.
 *
 * [TreeParser] parses, and [KstatesTreeWalker] builds outline, index and spans. [EmbeddedCode] handles the EVAL bodies,
 * [BlockFolding] folds the braces outside EVAL bodies, and [CommentFolding] folds comments. The class holds no state
 * and is thread-safe.
 */
internal class KstatesSyntaxAnalyzer : SyntaxAnalyzer {
    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES

    private val parser = TreeParser(DslGrammar.KSTATES, ::KstatesParser, KstatesParser::kstates_file)

    override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis {
        val tree = parser.parse(snapshot)
        val embedded = EmbeddedCode.of(tree)
        val collector = SyntaxCollector(embedded::isEmbedded)
        val outline = KstatesTreeWalker(tree, collector).walk(tree.root)
        embedded.recordReferences(collector)
        val comments = tree.tokens.filter { it.type == KstatesLexer.LINE_COMMENT }.mapNotNull { tree.rangeOf(it) }
        val folding = BlockFolding.of(tree, KstatesParser.LBRACE, KstatesParser.RBRACE, embedded::isEmbeddedToken) +
            embedded.folding() +
            CommentFolding.of(snapshot, comments)
        return collector.toAnalysis(tree.diagnostics, outline, folding)
    }
}
