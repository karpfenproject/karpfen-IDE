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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the syntax analyzers on the examples. Every example parses without PARSER diagnostics, and the documented
 * extraction rules for declarations, references, outline and spans hold on every example file.
 */
class ExamplesSyntaxTest {

    private val analyses: List<Pair<String, SyntaxAnalysis>> =
        Examples.files().map { it.path to analyze(it.language, it.text) }

    @Test
    fun `every example parses without syntax diagnostics`() {
        assertEquals(21, analyses.size)
        analyses.forEach { (path, analysis) -> assertEquals(emptyList(), analysis.syntaxDiagnostics, path) }
    }

    @Test
    fun `every example satisfies the outcome invariants and has an outline`() {
        Examples.files().zip(analyses).forEach { (file, analysis) ->
            checkInvariants(file.text, analysis.second, file.path)
            assertTrue(analysis.second.outline.isNotEmpty(), "no outline for ${file.path}")
        }
    }

    @Test
    fun `names are read from inside the quotes`() {
        Examples.files().zip(analyses).forEach { (file, pair) ->
            checkNameRanges(file.text, pair.second, file.path)
        }
    }

    @Test
    fun `every language's examples yield its kinds of declarations and references`() {
        fun kindsOf(language: KarpfenLanguage): Pair<Set<SymbolKind>, Set<ReferenceKind>> {
            val selected = Examples.files().zip(analyses).filter { it.first.language == language }.map { it.second.second.index }
            return selected.flatMap { it.declarations.map { d -> d.kind } }.toSet() to selected.flatMap { it.references.map { r -> r.kind } }.toSet()
        }
        val (kmetaDeclarations, kmetaReferences) = kindsOf(KarpfenLanguage.KMETA)
        assertEquals(setOf(SymbolKind.TYPE, SymbolKind.SIMPLE_PROPERTY, SymbolKind.HAS_RELATION, SymbolKind.KNOWS_RELATION), kmetaDeclarations)
        assertEquals(setOf(ReferenceKind.TYPE, ReferenceKind.PRIMITIVE_TYPE), kmetaReferences)

        val (kmodelDeclarations, kmodelReferences) = kindsOf(KarpfenLanguage.KMODEL)
        assertEquals(setOf(SymbolKind.OBJECT), kmodelDeclarations)
        assertEquals(setOf(ReferenceKind.TYPE, ReferenceKind.PROPERTY_KEY, ReferenceKind.RELATION_KEY, ReferenceKind.OBJECT), kmodelReferences)

        val (kstatesDeclarations, kstatesReferences) = kindsOf(KarpfenLanguage.KSTATES)
        assertEquals(
            setOf(SymbolKind.STATEMACHINE, SymbolKind.STATE, SymbolKind.MACRO, SymbolKind.MACRO_PARAMETER, SymbolKind.BINDING),
            kstatesDeclarations,
        )
        assertEquals(
            ReferenceKind.entries.toSet() - ReferenceKind.PROPERTY_KEY - ReferenceKind.OBJECT,
            kstatesReferences,
        )
    }
}

/**
 * The names of declarations and references equal the text inside their quotes. Model paths are named without blanks,
 * while their range keeps the blanks. String names sit directly between quotes, and EVAL references sit after `$(` or
 * `@`.
 */
internal fun checkNameRanges(text: String, analysis: SyntaxAnalysis, context: Any) {
    analysis.index.declarations.filter { it.kind != SymbolKind.STATEMACHINE && !it.nameRange.isEmpty }.forEach { declaration ->
        assertEquals(declaration.name.ifEmpty { "_" }, text.at(declaration.nameRange), "$declaration in $context")
        assertTrue(text[declaration.nameRange.start - 1] in QUOTES, "$declaration not after a quote in $context")
    }
    analysis.index.references.forEach { reference ->
        val written = text.at(reference.range)
        val expected = if (reference.kind == ReferenceKind.MODEL_PATH) written.filterNot { it == ' ' || it == '\t' } else written
        assertEquals(expected, reference.name, "$reference in $context")
        val before = text.getOrNull(reference.range.start - 1)
        assertTrue(before in QUOTES || before == '@' || before == '(' || before == ' ' || before == '\t', "$reference after '$before' in $context")
    }
}

private val QUOTES = setOf('"', '\'')
