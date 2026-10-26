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
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SemanticSpan
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SymbolReference
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [SyntaxCollector]: declarations with missing names, skipped and classified references, sorted spans without
 * overlaps, dropping findings inside embedded code, and the assembled analysis.
 */
class SyntaxCollectorTest {

    private fun quoted(start: Int, value: String) = QuotedString(value, TextRange.ofLength(start + 1, value.length), TextRange.ofLength(start, value.length + 2))

    @Test
    fun `declarations cover their name and missing names are empty at the construct start`() {
        val collector = SyntaxCollector()

        collector.declare(SymbolKind.STATE, quoted(6, "s"), TextRange(0, 5), container = "p", span = SemanticKind.STATE_NAME)
        collector.declare(SymbolKind.STATE, null, TextRange(20, 30), container = null, span = SemanticKind.STATE_NAME)

        assertEquals(
            listOf(
                SymbolDeclaration(SymbolKind.STATE, "s", TextRange(7, 8), TextRange(0, 8), "p"),
                SymbolDeclaration(SymbolKind.STATE, "", TextRange.empty(20), TextRange(20, 30), null),
            ),
            collector.index().declarations,
        )
        assertEquals(listOf(SemanticSpan(TextRange(7, 8), SemanticKind.STATE_NAME)), collector.spans())
    }

    @Test
    fun `missing references are skipped and type references are classified by name`() {
        val collector = SyntaxCollector()

        collector.reference(ReferenceKind.STATE, null)
        collector.typeReference(null)
        collector.typeReference(quoted(0, "number"))
        collector.typeReference(quoted(10, "boolean"))
        collector.typeReference(quoted(20, "Vector"))

        assertEquals(
            listOf(ReferenceKind.PRIMITIVE_TYPE to "number", ReferenceKind.PRIMITIVE_TYPE to "boolean", ReferenceKind.TYPE to "Vector"),
            collector.index().references.map { it.kind to it.name },
        )
        assertEquals(
            listOf(SemanticKind.PRIMITIVE_TYPE, SemanticKind.PRIMITIVE_TYPE, SemanticKind.TYPE_NAME),
            collector.spans().map { it.kind },
        )
    }

    @Test
    fun `spans are sorted, empty ones dropped and overlaps resolved in favour of the earlier span`() {
        val collector = SyntaxCollector()

        collector.span(TextRange(10, 14), SemanticKind.MODEL_PATH)
        collector.span(TextRange(2, 2), SemanticKind.STATE_NAME)
        collector.span(TextRange(0, 4), SemanticKind.TYPE_NAME)
        collector.span(TextRange(3, 6), SemanticKind.STATE_NAME)
        collector.span(null, SemanticKind.STATE_NAME)
        collector.span(TextRange(20, 21), null)

        assertEquals(
            listOf(SemanticSpan(TextRange(0, 4), SemanticKind.TYPE_NAME), SemanticSpan(TextRange(10, 14), SemanticKind.MODEL_PATH)),
            collector.spans(),
        )
    }

    @Test
    fun `findings inside embedded code are dropped, embedded references are kept`() {
        val body = TextRange(10, 20)
        val collector = SyntaxCollector { range -> range in body }

        collector.declare(SymbolKind.STATE, quoted(12, "in"), TextRange(11, 18), container = null, span = SemanticKind.STATE_NAME)
        collector.reference(ReferenceKind.STATE, quoted(14, "x"), span = SemanticKind.STATE_NAME)
        collector.span(TextRange(11, 12), SemanticKind.TYPE_NAME)
        collector.declare(SymbolKind.STATE, quoted(0, "out"), TextRange(0, 9), container = null)
        collector.embeddedReference(ReferenceKind.MODEL_PATH, "a->b", TextRange(15, 19), SemanticKind.MODEL_PATH)

        assertEquals(listOf("out"), collector.index().declarations.map { it.name })
        assertEquals(listOf(SymbolReference(ReferenceKind.MODEL_PATH, "a->b", TextRange(15, 19), null)), collector.index().references)
        assertEquals(listOf(SemanticSpan(TextRange(15, 19), SemanticKind.MODEL_PATH)), collector.spans())
    }

    @Test
    fun `the analysis sorts diagnostics and folding and removes duplicate folds`() {
        val collector = SyntaxCollector()
        val late = Diagnostic(TextRange(5, 6), Severity.ERROR, "b", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
        val early = late.copy(range = TextRange(1, 2), message = "a")
        val outer = FoldingRange(TextRange(0, 30), FoldingKind.BLOCK)
        val inner = FoldingRange(TextRange(0, 10), FoldingKind.COMMENT)

        val analysis = collector.toAnalysis(listOf(late, early), emptyList(), listOf(outer, inner, outer))

        assertEquals(listOf(early, late), analysis.syntaxDiagnostics)
        assertEquals(listOf(inner, outer), analysis.folding)
        assertTrue(analysis.index.declarations.isEmpty() && analysis.semanticSpans.isEmpty())
    }
}
