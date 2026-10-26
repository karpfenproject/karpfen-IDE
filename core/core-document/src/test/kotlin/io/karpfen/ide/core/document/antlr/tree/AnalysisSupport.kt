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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxAnalyzer
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.assertTrue
import kotlin.test.fail

/** The three analyzers under test, by language. */
internal val analyzers: Map<KarpfenLanguage, SyntaxAnalyzer> =
    listOf(KmetaSyntaxAnalyzer(), KmodelSyntaxAnalyzer(), KstatesSyntaxAnalyzer()).associateBy { it.language }

/** Analyzes [text] as [language] (version 0). */
internal fun analyze(language: KarpfenLanguage, text: String): SyntaxAnalysis =
    analyzers.getValue(language).analyze(TextSnapshot(text, 0))

/** The text of [range] in [text]. */
internal fun String.at(range: TextRange): String = substring(range.start, range.end)

/** Every outline node of [nodes] and their descendants, depth first in document order. */
internal fun flatten(nodes: List<OutlineNode>): List<OutlineNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

/**
 * Checks the documented invariants of an analysis of [text] (see `ParseOutcome`): every range lies inside the
 * text; outline children lie inside their parent and are in document order; spans are sorted, non-empty and do not
 * overlap; folding ranges are sorted and span more than one line; diagnostics are sorted. ([OutlineNode] and
 * `SymbolDeclaration` check the selection/name containment themselves on construction.)
 */
internal fun checkInvariants(text: String, analysis: SyntaxAnalysis, context: Any) {
    fun inside(range: TextRange, what: Any) {
        if (range.end > text.length) fail("$what: $range outside the text (length ${text.length}) in $context")
    }
    analysis.syntaxDiagnostics.forEach { inside(it.range, it) }
    assertTrue(analysis.syntaxDiagnostics == analysis.syntaxDiagnostics.sortedWith(Diagnostic.ORDER), "unsorted diagnostics in $context")
    checkOutline(analysis.outline, parent = null, ::inside, context)
    analysis.index.declarations.forEach { inside(it.range, it) }
    analysis.index.references.forEach { inside(it.range, it) }
    analysis.semanticSpans.forEach { inside(it.range, it) }
    analysis.semanticSpans.zipWithNext().forEach { (a, b) ->
        assertTrue(a.range.end <= b.range.start, "overlapping spans $a, $b in $context")
    }
    assertTrue(analysis.semanticSpans.none { it.range.isEmpty }, "empty span in $context")
    val lines = TextSnapshot(text, 0).lineIndex
    analysis.folding.forEach { fold ->
        inside(fold.range, fold)
        assertTrue(lines.lineOf(fold.range.start) < lines.lineOf(fold.range.end), "single-line $fold in $context")
    }
    assertTrue(analysis.folding.zipWithNext().all { (a, b) -> a.range <= b.range }, "folding not sorted in $context")
}

private fun checkOutline(nodes: List<OutlineNode>, parent: OutlineNode?, inside: (TextRange, Any) -> Unit, context: Any) {
    nodes.forEach { node ->
        inside(node.range, node)
        if (parent != null) assertTrue(node.range in parent.range, "child ${node.range} outside parent ${parent.range} in $context")
        checkOutline(node.children, node, inside, context)
    }
    assertTrue(nodes.zipWithNext().all { (a, b) -> a.range.start <= b.range.start }, "outline not in document order in $context")
}
