package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.tree.checkInvariants
import io.karpfen.ide.core.document.antlr.tree.flatten
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxAnalysis
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the environment language's syntax analysis: outline, folding and semantic spans of the descriptor fixtures,
 * syntax diagnostics of a broken one, the wiring in the registry, and robustness on mutations of every descriptor
 * fixture and on every prefix of one.
 */
class EnvironmentSyntaxAnalyzerTest {

    private val analyzer = EnvironmentSyntaxAnalyzer()
    private val valid = DescriptorExamples.text(DescriptorExamples.VALID)

    private fun analyze(text: String): SyntaxAnalysis = analyzer.analyze(TextSnapshot(text, 0))

    @Test
    fun `the outline lists every key, nests objects and lists, and names entries by their file`() {
        val outline = analyze(valid).outline

        assertEquals(
            listOf("metamodel", "events", "model", "stateMachines", "tickDelayMs", "eventTtlMs", "observe", "runtime", "bridges"),
            outline.map { it.name },
        )
        assertTrue(outline.all { it.kind == SymbolKind.KEY })
        assertEquals(
            listOf("cleaning_robot.kmeta", "EVENTS.kmeta", "cleaning_robot.kmodel", null, "50", "3000", null, null, null),
            outline.map { it.detail },
        )
        val entry = outline[3].children.single()
        assertEquals(SymbolKind.ENTRY to "cleaning_robot.kstates", entry.kind to entry.name)
        assertEquals(listOf("file" to "cleaning_robot.kstates", "attachedTo" to "turtle"), entry.children.map { it.name to it.detail })
        assertEquals(listOf("objects", "domains"), outline[6].children.map { it.name })
        assertEquals(listOf("public"), outline[6].children[1].children.map { it.name })
        val metamodel = outline[0]
        assertEquals("metamodel", valid.substring(metamodel.selectionRange.start, metamodel.selectionRange.end))
        assertEquals("\"metamodel\": \"cleaning_robot.kmeta\"", valid.substring(metamodel.range.start, metamodel.range.end))
    }

    @Test
    fun `a state machine entry is named by its file whatever the order of its keys`() {
        val text = "{\"stateMachines\": [ { \"attachedTo\": \"t\", \"file\": \"x.kstates\" } ]}"

        val entry = analyze(text).outline.single().children.single()

        assertEquals("x.kstates", entry.name)
        assertEquals("x.kstates", text.substring(entry.selectionRange.start, entry.selectionRange.end))
    }

    @Test
    fun `a stray token before the root keeps the outline, the folding and the spans`() {
        listOf("x ", "}").forEach { stray ->
            val text = stray + valid

            val analysis = analyze(text)

            assertEquals(analyze(valid).outline.map { it.name }, analysis.outline.map { it.name }, "'$stray'")
            assertEquals(2, analysis.folding.size, "'$stray'")
            assertEquals(analyze(valid).semanticSpans.size, analysis.semanticSpans.size, "'$stray'")
            assertEquals(1, analysis.syntaxDiagnostics.size, "one error at the stray token: ${analysis.syntaxDiagnostics}")
            assertEquals(0, analysis.syntaxDiagnostics.single().range.start)
        }
    }

    @Test
    fun `entries without a string are named by their position`() {
        val outline = analyze("{\"x\": [1, {\"a\": 2}, \"\"]}").outline

        assertEquals(listOf("1", "[1]", "[2]"), outline.single().children.map { it.name })
    }

    @Test
    fun `semantic spans mark keys, file paths, object keys and event domains`() {
        val spans = analyze(valid).semanticSpans.map { it.kind to valid.substring(it.range.start, it.range.end) }

        val keys = spans.filter { it.first == SemanticKind.PROPERTY_NAME }.map { it.second }
        assertEquals(
            listOf(
                "metamodel", "events", "model", "stateMachines", "file", "attachedTo", "tickDelayMs", "eventTtlMs", "observe",
                "objects", "domains", "runtime", "pythonPackages", "bridges",
            ),
            keys,
        )
        assertEquals(
            listOf(
                SemanticKind.FILE_PATH to "cleaning_robot.kmeta", SemanticKind.FILE_PATH to "EVENTS.kmeta",
                SemanticKind.FILE_PATH to "cleaning_robot.kmodel", SemanticKind.FILE_PATH to "cleaning_robot.kstates",
                SemanticKind.OBJECT_NAME to "turtle", SemanticKind.EVENT_DOMAIN to "public",
            ),
            spans.filter { it.first != SemanticKind.PROPERTY_NAME },
            "the numbers, the pattern \"*\" and the package \"numpy\" keep their lexical style",
        )
    }

    @Test
    fun `closed objects and lists over several lines fold`() {
        val folding = analyze(valid).folding

        assertEquals(listOf(FoldingKind.BLOCK, FoldingKind.BLOCK), folding.map { it.kind })
        assertEquals(listOf(valid.trimEnd(), valid.substring(valid.indexOf('['), valid.indexOf(']') + 1)), folding.map { valid.substring(it.range.start, it.range.end) })
    }

    @Test
    fun `a truncated descriptor reports one syntax error and keeps its outline`() {
        val text = DescriptorExamples.text(DescriptorExamples.INVALID_JSON)

        val analysis = analyze(text)

        val error = analysis.syntaxDiagnostics.single()
        assertEquals(DiagnosticSource.PARSER, error.source)
        assertEquals(text.trimEnd().length, error.range.start)
        assertEquals(listOf("metamodel", "model"), analysis.outline.map { it.name })
        assertEquals(SyntaxIndex.EMPTY, analysis.index)
        assertEquals(emptyList(), analysis.folding, "an object that is not closed does not fold")
    }

    @Test
    fun `the registry wires the environment language for descriptor files`() {
        val registry = defaultLanguageRegistry()
        val support = registry.supportFor("robot.kenv.json")!!

        val outcome = support.parser.parse(TextSnapshot(valid, 4), SemanticMode.STANDALONE)
        val tokens = support.tokenizer.tokenize(TextSnapshot(valid, 4))

        assertEquals(KarpfenLanguage.ENVIRONMENT, outcome.language)
        assertEquals(4L, outcome.version)
        assertTrue(outcome.isSyntaxClean)
        assertNull(outcome.standaloneModel, "descriptors have no standalone semantics")
        assertEquals(emptyList(), tokens.diagnostics)
        assertTrue(flatten(outcome.outline).any { it.kind == SymbolKind.ENTRY })
    }

    @Test
    fun `the analysis never throws and keeps its invariants on mutations of every descriptor fixture`(reporter: TestReporter) {
        var count = 0
        for (name in DescriptorExamples.names()) {
            val example = DescriptorExamples.text(name)
            Mutations.random(example, seed = SEED + name.length, count = MUTATIONS).forEach { mutation ->
                checked(mutation.text, "$name, $mutation")
                count++
            }
        }
        reporter.publishEntry("descriptor mutations", "$count analyzed")
    }

    @Test
    fun `the analysis never throws and keeps its invariants on every prefix of a descriptor`() {
        Mutations.prefixes(valid).forEach { prefix -> checked(prefix, "prefix of length ${prefix.length}") }
    }

    /** Analyzes [text], failing on an exception, and checks the invariants of every syntax analysis. */
    private fun checked(text: String, label: String) {
        val analysis = try {
            analyze(text)
        } catch (failure: Exception) {
            fail("$label threw $failure", failure)
        }
        checkInvariants(text, analysis, label)
    }

    private companion object {
        const val MUTATIONS = 3_000
        const val SEED = 20_261_023L
    }
}
