package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
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
 * Covers [ComposedDocumentParser]: when the standalone conversion runs, how its diagnostics are passed on, and how
 * failures of the analyzer or the conversion (stack overflows included) become internal-error diagnostics.
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

    private class FakeSyntax(private val result: () -> SyntaxAnalysis) : SyntaxAnalyzer {
        override val language: KarpfenLanguage = KarpfenLanguage.KMETA
        override fun analyze(snapshot: TextSnapshot): SyntaxAnalysis = result()
    }

    private class RecordingSemantics(private val result: () -> StandaloneResult) : StandaloneSemantics {
        val calls = ArrayList<Pair<TextSnapshot, SyntaxIndex>>()
        override fun convert(snapshot: TextSnapshot, index: SyntaxIndex): StandaloneResult {
            calls += snapshot to index
            return result()
        }
    }

    private val model = DslModel.Kmeta(meta.Metamodel(mutableListOf(), meta.ClassType("Root", "", mutableListOf(), mutableListOf())))
    private val dslError = Diagnostic(TextRange(6, 7), Severity.ERROR, "bad 'A'", DiagnosticCodes.DSL_TOOLS_ERROR, DiagnosticSource.DSL_TOOLS)

    @Test
    fun `syntax only by default never converts`() {
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
    fun `standalone mode converts clean syntax with the outcome's index`() {
        val semantics = RecordingSemantics { StandaloneResult(model, emptyList()) }
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, semantics)

        val outcome = parser.parse(snapshot, SemanticMode.STANDALONE)

        assertSame(model, outcome.standaloneModel)
        assertEquals(emptyList(), outcome.standaloneDiagnostics)
        assertSame(snapshot, semantics.calls.single().first)
        assertSame(clean.index, semantics.calls.single().second)
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
    fun `syntax errors or missing semantics skip the conversion`() {
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
    fun `a failing conversion becomes a DSL_TOOLS internal error`() {
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { clean }, RecordingSemantics { throw IllegalStateException("adapter bug") })

        val outcome = parser.parse(snapshot, SemanticMode.STANDALONE)

        assertTrue(outcome.isSyntaxClean)
        assertNull(outcome.standaloneModel)
        val diagnostic = outcome.standaloneDiagnostics.single()
        assertEquals(DiagnosticCodes.INTERNAL_ERROR to DiagnosticSource.DSL_TOOLS, diagnostic.code to diagnostic.source)
        assertTrue("adapter bug" in diagnostic.message)
    }

    @Test
    fun `a stack overflow of the analyzer is reported, not thrown`() {
        val parser = ComposedDocumentParser(KarpfenLanguage.KMETA, FakeSyntax { throw StackOverflowError() }, semantics = null)

        val diagnostic = parser.parse(snapshot).syntaxDiagnostics.single()

        assertEquals(DiagnosticCodes.INTERNAL_ERROR to DiagnosticSource.PARSER, diagnostic.code to diagnostic.source)
        assertTrue("StackOverflowError" in diagnostic.message)
    }

    @Test
    fun `pathologically deep nesting is reported, not thrown, by the real parser`() {
        val depth = 5_000
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n" + "STATE \"s\" {\n".repeat(depth) + "}\n".repeat(depth) + " }\n TRANSITIONS { }\n}"
        val parser = ComposedDocumentParser(KarpfenLanguage.KSTATES, KstatesSyntaxAnalyzer(), semantics = null)
        var outcome: ParseOutcome? = null
        val smallStack = Thread(null, { outcome = parser.parse(TextSnapshot(text, 0)) }, "small-stack", 256L * 1024)

        smallStack.start()
        smallStack.join()

        val diagnostic = outcome!!.syntaxDiagnostics.single()
        assertEquals(DiagnosticCodes.INTERNAL_ERROR to DiagnosticSource.PARSER, diagnostic.code to diagnostic.source)
    }

    @Test
    fun `the analyzer must parse the parser's language`() {
        assertFailsWith<IllegalArgumentException> { ComposedDocumentParser(KarpfenLanguage.KSTATES, FakeSyntax { clean }, null) }
    }
}
