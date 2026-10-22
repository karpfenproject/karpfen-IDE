package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.tree.KmetaSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KmodelSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.dsl.KmetaStandaloneSemantics
import io.karpfen.ide.core.document.dsl.KstatesStandaloneSemantics
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parsers as `registry/KarpfenLanguageSupports.kt` wires them (syntax analyzer + dsl-tools standalone semantics),
 * on the examples: zero PARSER diagnostics everywhere; with [SemanticMode.STANDALONE] every example
 * `.kmeta` except `EVENTS.kmeta` (whose payloads reference domain types) and every `.kstates` converts.
 */
class StandaloneParseTest {

    private val converter = DslConverter()
    private val parsers: Map<KarpfenLanguage, DocumentParser> = mapOf(
        KarpfenLanguage.KMETA to ComposedDocumentParser(KarpfenLanguage.KMETA, KmetaSyntaxAnalyzer(), KmetaStandaloneSemantics(converter)),
        KarpfenLanguage.KMODEL to ComposedDocumentParser(KarpfenLanguage.KMODEL, KmodelSyntaxAnalyzer(), semantics = null),
        KarpfenLanguage.KSTATES to ComposedDocumentParser(KarpfenLanguage.KSTATES, KstatesSyntaxAnalyzer(), KstatesStandaloneSemantics(converter)),
    )

    @Test
    fun `every example parses syntax-only without diagnostics or conversion`() {
        Examples.files().forEach { file ->
            val outcome = parsers.getValue(file.language).parse(TextSnapshot(file.text, 3))
            assertEquals(emptyList(), outcome.syntaxDiagnostics, file.path)
            assertEquals(3, outcome.version)
            assertEquals(file.language, outcome.language)
            assertNull(outcome.standaloneModel, file.path)
            assertTrue(outcome.standaloneDiagnostics.isEmpty())
        }
    }

    @Test
    fun `every example metamodel and state machine converts standalone`() {
        val convertible = Examples.files().filter { it.language != KarpfenLanguage.KMODEL && !it.isEventsMetamodel }
        assertEquals(11, convertible.size, "6 metamodels and 5 state machines")
        convertible.forEach { file ->
            val outcome = parsers.getValue(file.language).parse(TextSnapshot(file.text, 0), SemanticMode.STANDALONE)
            assertEquals(emptyList(), outcome.standaloneDiagnostics, file.path)
            when (file.language) {
                KarpfenLanguage.KMETA -> assertIs<DslModel.Kmeta>(outcome.standaloneModel, file.path)
                else -> assertIs<DslModel.Kstates>(outcome.standaloneModel, file.path)
            }
        }
    }

    @Test
    fun `models are never converted on their own`() {
        Examples.files(KarpfenLanguage.KMODEL).forEach { file ->
            assertNull(parsers.getValue(KarpfenLanguage.KMODEL).parse(TextSnapshot(file.text, 0), SemanticMode.STANDALONE).standaloneModel)
        }
    }

    @Test
    fun `an events metamodel converted on its own reports the domain type it needs at its name`() {
        val events = Examples.file("karpfen-runtime/single_robot_example/EVENTS.kmeta").text

        val outcome = parsers.getValue(KarpfenLanguage.KMETA).parse(TextSnapshot(events, 0), SemanticMode.STANDALONE)

        assertTrue(outcome.isSyntaxClean)
        assertNull(outcome.standaloneModel)
        val diagnostic = outcome.standaloneDiagnostics.single()
        assertEquals(DiagnosticSource.DSL_TOOLS to DiagnosticCodes.DSL_TOOLS_ERROR, diagnostic.source to diagnostic.code)
        assertTrue("'Vector'" in diagnostic.message, diagnostic.message)
        assertEquals("Vector", events.substring(diagnostic.range.start, diagnostic.range.end))
        assertEquals('"', events[diagnostic.range.start - 1])
    }

    @Test
    fun `a semantic error of a state machine is located and leaves the syntax outcome intact`() {
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n  STATE \"a\" { }\n }\n TRANSITIONS { }\n}"

        val outcome = parsers.getValue(KarpfenLanguage.KSTATES).parse(TextSnapshot(text, 0), SemanticMode.STANDALONE)

        assertTrue(outcome.isSyntaxClean)
        assertNull(outcome.standaloneModel)
        val diagnostic = outcome.standaloneDiagnostics.single()
        assertEquals(DiagnosticSource.DSL_TOOLS, diagnostic.source)
        assertEquals("STATEMACHINE", text.substring(diagnostic.range.start, diagnostic.range.end), "no quoted name: the first token")
        assertEquals(listOf("a"), outcome.index.declarationsOf(SymbolKind.STATE).map { it.name })
    }
}
