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
 * Parses KStates with the generated dsl-tools [KstatesParser] (default error recovery) on a `CommonTokenStream` of
 * `KarpfenLexers.create`, with one `IdeErrorListener` attached to lexer and parser, and walks the tree into outline,
 * index, folding and semantic spans. Tolerates conjured "missing" tokens (negative indices) and error
 * nodes; token offsets are converted with `CodePointOffsets`.
 *
 * Embedded Python is taken from the shared lexical scanner, not from the tree, so references agree with the tokenizer
 * on broken text too: `EvalBodies.find`/`scan` over the stream's tokens (every channel) yield MODEL_PATH references for
 * PATH_REFERENCE pieces (name = `EmbeddedPiece.name`, range = `EmbeddedPiece.inner`), MACRO references for
 * MACRO_REFERENCE pieces and EMBEDDED_CODE folding for multi-line bodies. COMMENT folding uses the hidden
 * `LINE_COMMENT` tokens.
 *
 * Collaborators: [TreeParser] (parse), [KstatesTreeWalker] (outline, index, spans), [EmbeddedCode] (EVAL bodies),
 * [BlockFolding] (braces outside EVAL bodies) and [CommentFolding]. Stateless and thread-safe.
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
