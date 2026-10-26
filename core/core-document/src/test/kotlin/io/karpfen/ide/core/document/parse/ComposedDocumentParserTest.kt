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
package io.karpfen.ide.core.document.parse

import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [ComposedDocumentParser]: when the standalone read runs, how its diagnostics are passed on, how failures of
 * the analyzer or the read become internal-error diagnostics, and how a text beyond the nesting limit and a stack
 * overflow of the analyzer or the read become nesting-too-deep diagnostics.
 */
class ComposedDocumentParserTest {

    private val snapshot = TextSnapshot("type \"A\" \"d\" { }", version = 7)

    private val node = OutlineNode(SymbolKind.TYPE, "A", "d", TextRange(0, 16), TextRange(6, 7), emptyList())
    private val clean = SyntaxAnalysis(
        syntaxDiagnostics = emptyList(),
        outline = listOf(node),
        index = SyntaxIndex(listOf(SymbolDeclaration(SymbolKind.TYPE, "A", TextRange(6, 7), TextRange(0, 16), null)), emptyList()),
        folding = emptyList(),
        semanticSpans = listOf(SemanticSpan(TextRange(6, 7), SemanticKind.TYPE_NAME)),
    )
    private val syntaxError = Diagnostic(TextRange(13, 14), Severity.ERROR, "missing", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)

    /** A syntax analyzer that returns what [result] gives. */
    private class FakeSyntax(private val result: () -> SyntaxAnalysis) : SyntaxAnalyzer {
        override val language: KarpfenLanguage = KarpfenLanguage.KMETA
        override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis = result()
    }

    /** A standalone read that returns what [result] gives and records the snapshot of every call in [calls]. */
    private class RecordingSemantics(private val result: () -> StandaloneResult) : StandaloneSemantics {
        val calls = ArrayList<TextSnapshot>()
        override fun read(snapshot: TextSnapshot): StandaloneResult {
            calls += snapshot
            return result()
        }
    }

    private val model = DslModel.Kmeta(Metamodel(mutableListOf(), ClassType("Root", "", mutableListOf(), mutableListOf())))
    private val dslError = Diagnostic(TextRange(6, 7), Severity.ERROR, "bad 'A'", "kmeta.unknown-target-type", DiagnosticSource.DSL_TOOLS)

    @Test
    fun `syntax only by default never reads with dsl-tools`() {
        val semantics = RecordingSemantics { StandaloneResult(model, emptyList()) }
        val parser: DocumentParser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, semantics)

        val outcome = parser.parse(snapshot)

        assertEquals(
            ParseOutcome(KarpfenLanguage.KMETA, 7, emptyList(), listOf(node), clean.index, emptyList(), clean.semanticSpans, null, emptyList()),
            outcome,
        )
        assertTrue(semantics.calls.isEmpty())
    }

    @Test
    fun `standalone mode reads clean syntax`() {
        val semantics = RecordingSemantics { StandaloneResult(model, emptyList()) }
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, semantics)

        val outcome = parser.parse(snapshot, SemanticMode.STANDALONE)

        assertSame(model, outcome.standaloneModel)
        assertEquals(emptyList(), outcome.standaloneDiagnostics)
        assertSame(snapshot, semantics.calls.single())
    }

    @Test
    fun `standalone diagnostics are passed on sorted`() {
        val later = dslError.copy(range = TextRange(10, 11), message = "later")
        val parser = ComposedDocumentParser(
            KarpfenLanguage.KMETA, FakeSyntax { clean }, RecordingSemantics { StandaloneResult(null, listOf(later, dslError)) },
        )

        val outcome = parser.parse(snapshot, SemanticMode.STANDALONE)

        assertNull(outcome.standaloneModel)
        assertEquals(listOf(dslError, later), outcome.standaloneDiagnostics)
        assertTrue(outcome.isSyntaxClean)
    }

    @Test
    fun `syntax errors or missing semantics skip the read`() {
        val semantics = RecordingSemantics { StandaloneResult(model, emptyList()) }
        val broken = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean.copy(syntaxDiagnostics = listOf(syntaxError)) }, semantics)

        val outcome = broken.parse(snapshot, SemanticMode.STANDALONE)

        assertEquals(listOf(syntaxError), outcome.syntaxDiagnostics)
        assertFalse(outcome.isSyntaxClean)
        assertNull(outcome.standaloneModel)
        assertTrue(semantics.calls.isEmpty())
        val withoutSemantics = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, semantics = null)
        assertNull(withoutSemantics.parse(snapshot, SemanticMode.STANDALONE).standaloneModel)
    }

    @Test
    fun `a failing syntax analyzer becomes a PARSER internal error`() {
        val semantics = RecordingSemantics { StandaloneResult(model, emptyList()) }
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { error("walker bug") }, semantics)

        val outcome = parser.parse(snapshot, SemanticMode.STANDALONE)

        val diagnostic = outcome.syntaxDiagnostics.single()
        assertEquals(DiagnosticCodes.INTERNAL_ERROR, diagnostic.code)
        assertEquals(DiagnosticSource.PARSER, diagnostic.source)
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertTrue("walker bug" in diagnostic.message)
        assertEquals(7, outcome.version)
        assertEquals(SyntaxIndex.EMPTY, outcome.index)
        assertTrue(outcome.outline.isEmpty() && semantics.calls.isEmpty())
    }

    @Test
    fun `a failing read becomes a DSL_TOOLS internal error`() {
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, RecordingSemantics { throw IllegalStateException("adapter bug") })

        val outcome = parser.parse(snapshot, SemanticMode.STANDALONE)

        assertTrue(outcome.isSyntaxClean)
        assertNull(outcome.standaloneModel)
        val diagnostic = outcome.standaloneDiagnostics.single()
        assertEquals(DiagnosticCodes.INTERNAL_ERROR to DiagnosticSource.DSL_TOOLS, diagnostic.code to diagnostic.source)
        assertEquals("Reading the KMeta document failed internally: adapter bug", diagnostic.message)
    }

    @Test
    fun `a stack overflow of the analyzer or of the read is reported as nesting too deep, not thrown`() {
        val analyzer = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { throw StackOverflowError() }, semantics = null)
        val reader = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, RecordingSemantics { throw StackOverflowError() })

        val syntax = analyzer.parse(snapshot).syntaxDiagnostics.single()
        val read = reader.parse(snapshot, SemanticMode.STANDALONE)

        assertEquals(DiagnosticCodes.NESTING_TOO_DEEP to DiagnosticSource.PARSER, syntax.code to syntax.source)
        assertEquals(Severity.ERROR to TextRange.EMPTY, syntax.severity to syntax.range)
        assertEquals("The KMeta document nests deeper than the parser can follow; reduce the nesting", syntax.message)
        assertNull(read.standaloneModel)
        val diagnostic = read.standaloneDiagnostics.single()
        assertEquals(DiagnosticCodes.NESTING_TOO_DEEP to DiagnosticSource.DSL_TOOLS, diagnostic.code to diagnostic.source)
    }

    @Test
    fun `a text beyond the nesting limit is reported at the brace the analyzer names`() {
        val brace = TextRange(13, 14)
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { throw NestingLimitExceeded(brace) }, semantics = null)

        val outcome = parser.parse(snapshot)

        val diagnostic = outcome.syntaxDiagnostics.single()
        assertEquals(DiagnosticCodes.NESTING_TOO_DEEP to DiagnosticSource.PARSER, diagnostic.code to diagnostic.source)
        assertEquals(Severity.ERROR to brace, diagnostic.severity to diagnostic.range)
        assertEquals("The KMeta document nests deeper than ${NestingLimit.MAX_DEPTH} levels; reduce the nesting", diagnostic.message)
        assertFalse(outcome.isSyntaxClean)
    }


    @Test
    fun `the analyzer must parse the parser's language`() {
        assertFailsWith<IllegalArgumentException> { ComposedDocumentParser(KarpfenLanguage.KSTATES, FakeSyntax { clean }, null) }
    }
}
