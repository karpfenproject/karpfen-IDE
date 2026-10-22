package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import org.junit.jupiter.api.TestReporter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers reading environment descriptors: the shared examples, test-local edge cases (missing, unknown, nested and
 * repeated keys, values of the wrong type, references) and robustness on mutated and truncated examples.
 */
class EnvironmentDescriptorReaderTest {

    private val reader = EnvironmentDescriptorReader()
    private val path = Path.of("/ws/robot.kenv.json").toAbsolutePath()

    /** The range of [quoted] inside its quotes in [text]. */
    private fun rangeOf(text: String, quoted: String): TextRange {
        val start = text.indexOf("\"$quoted\"").also { require(it >= 0) { "\"$quoted\" not in text" } } + 1
        return TextRange.ofLength(start, quoted.length)
    }

    @Test
    fun `the valid example decodes without findings and lists its file references with value ranges`() {
        val text = DescriptorExamples.text(DescriptorExamples.VALID)

        val result = reader.read(path, text)

        val descriptor = assertNotNull(result.descriptor)
        assertEquals("cleaning_robot.kmeta", descriptor.metamodel)
        assertEquals("EVENTS.kmeta", descriptor.events)
        assertEquals(listOf(EnvironmentDescriptor.StateMachineEntry("cleaning_robot.kstates", DescriptorExamples.ATTACHED_TO)), descriptor.stateMachines)
        assertEquals(50L, descriptor.tickDelayMs)
        assertEquals(EnvironmentDescriptor.ObserveSpec("*", listOf("public")), descriptor.observe)
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(
            listOf(
                DescriptorFileReference(DescriptorFileRole.METAMODEL, "cleaning_robot.kmeta", rangeOf(text, "cleaning_robot.kmeta")),
                DescriptorFileReference(DescriptorFileRole.EVENTS, "EVENTS.kmeta", rangeOf(text, "EVENTS.kmeta")),
                DescriptorFileReference(DescriptorFileRole.MODEL, "cleaning_robot.kmodel", rangeOf(text, "cleaning_robot.kmodel")),
                DescriptorFileReference(DescriptorFileRole.STATE_MACHINE, "cleaning_robot.kstates", rangeOf(text, "cleaning_robot.kstates")),
            ),
            result.references,
        )
    }

    @Test
    fun `an unknown key is a warning at the key and the descriptor still decodes`() {
        val text = DescriptorExamples.text(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)

        val result = reader.read(path, text)

        assertNotNull(result.descriptor)
        val warning = result.diagnostics.single()
        assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY, warning.code)
        assertEquals(Severity.WARNING, warning.severity)
        assertEquals(DiagnosticSource.IDE, warning.source)
        assertEquals(rangeOf(text, "tickDelay"), warning.range)
        assertTrue("tickDelayMs" in warning.message, "the message lists the known keys: ${warning.message}")
        val model = result.references.single { it.role == DescriptorFileRole.MODEL }
        assertEquals(DescriptorFileReference(DescriptorFileRole.MODEL, "missing.kmodel", rangeOf(text, "missing.kmodel")), model)
    }

    @Test
    fun `invalid JSON is the syntax error the editor shows and no descriptor`() {
        val text = DescriptorExamples.text(DescriptorExamples.INVALID_JSON)

        val result = reader.read(path, text)

        assertNull(result.descriptor)
        assertEquals(emptyList(), result.references)
        val error = result.diagnostics.single()
        assertEquals(DiagnosticCodes.SYNTAX_ERROR to DiagnosticSource.PARSER, error.code to error.source)
        assertEquals(Severity.ERROR, error.severity)
        assertEquals(text.trimEnd().length, error.range.start, "at the truncation: ${error.message}")
        val editor = defaultLanguageRegistry().support(KarpfenLanguage.ENVIRONMENT).parser.parse(TextSnapshot(text, 0))
        assertEquals(editor.syntaxDiagnostics, result.diagnostics, "one syntax rule for the reader and the editor")
    }

    @Test
    fun `a missing required key is an error at the object`() {
        val text = "  {\n  \"metamodel\": \"a.kmeta\"\n}\n"

        val result = reader.read(path, text)

        assertNull(result.descriptor)
        val error = result.diagnostics.single()
        assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, error.code)
        assertEquals(TextRange(2, 3), error.range)
        assertTrue("'model'" in error.message, error.message)
    }

    @Test
    fun `nested unknown and missing keys are found at their places`() {
        val text = """
            {"metamodel": "a.kmeta", "model": "b.kmodel",
             "stateMachines": [{"file": "c.kstates", "attachedTo": "x", "extra": 1}, {"file": "d.kstates"}],
             "observe": {"objects": "*", "colour": "red"}}
        """.trimIndent()

        val result = reader.read(path, text)

        assertNull(result.descriptor)
        assertEquals(
            listOf(
                Triple(WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY, Severity.WARNING, rangeOf(text, "extra")),
                Triple(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, Severity.ERROR, TextRange.ofLength(text.indexOf("{\"file\": \"d"), 1)),
                Triple(WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY, Severity.WARNING, rangeOf(text, "colour")),
            ),
            result.diagnostics.map { Triple(it.code, it.severity, it.range) },
        )
        assertTrue("stateMachines[1]" in result.diagnostics[1].message, result.diagnostics[1].message)
    }

    @Test
    fun `a value of the wrong type is an error and no descriptor`() {
        val text = "{\"metamodel\": \"a.kmeta\", \"model\": \"b.kmodel\", \"stateMachines\": {\"file\": \"c\"}}"

        val result = reader.read(path, text)

        assertNull(result.descriptor)
        val error = result.diagnostics.single()
        assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, error.code)
        assertTrue(error.range.start in 0..text.length && error.range.end <= text.length)
    }

    @Test
    fun `a repeated key is an error at the repetition, also in nested objects, and no descriptor`() {
        val text = """
            {"metamodel": "a.kmeta", "metamodel": "b.kmeta", "model": "a.kmodel",
             "stateMachines": [{"file": "m.kstates", "attachedTo": "x", "file": "n.kstates"}]}
        """.trimIndent()

        val result = reader.read(path, text)

        assertNull(result.descriptor, "the IDE must not analyze another metamodel than a reader taking the first value")
        assertEquals(emptyList(), result.references)
        assertEquals(
            listOf(
                TextRange.ofLength(text.indexOf("metamodel", startIndex = text.indexOf("metamodel") + 1), "metamodel".length),
                TextRange.ofLength(text.lastIndexOf("file"), "file".length),
            ),
            result.diagnostics.map { it.range },
        )
        assertTrue(result.diagnostics.all { it.code == WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID && it.severity == Severity.ERROR })
        assertTrue("stateMachines[0]" in result.diagnostics[1].message, result.diagnostics[1].message)
    }

    @Test
    fun `a value of the wrong type inside a nested object or array is reported at that value`() {
        val required = "\"metamodel\": \"a.kmeta\", \"model\": \"a.kmodel\""
        val machine = "{\"file\": \"m.kstates\", \"attachedTo\": \"x\"}"
        val cases = listOf(
            "{$required, \"stateMachines\": [$machine, {\"file\": 3, \"attachedTo\": \"x\"}]}" to "3",
            "{$required, \"observe\": {\"objects\": [\"*\"]}}" to "[\"*\"]",
            "{$required, \"stateMachines\": [$machine], \"bridges\": [{\"file\": \"b.json\"}]}" to "{\"file\": \"b.json\"}",
        )

        for ((text, value) in cases) {
            val error = reader.read(path, text).diagnostics.single()

            assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, error.code, text)
            assertEquals(TextRange.ofLength(text.indexOf(value), value.length), error.range, "${error.message} in $text")
        }
    }

    @Test
    fun `references are in document order, decoded, with ranges of the raw string content`() {
        val text = "{\"model\": \"\\u0062.kmodel\", \"bridges\": [\"mqtt.kbridge.json\"], \"metamodel\": \"dir\\/a.kmeta\", \"events\": null}"

        val result = reader.read(path, text)

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf(DescriptorFileRole.MODEL, DescriptorFileRole.BRIDGE, DescriptorFileRole.METAMODEL), result.references.map { it.role })
        assertEquals(listOf("b.kmodel", "mqtt.kbridge.json", "dir/a.kmeta"), result.references.map { it.value })
        assertEquals(listOf("\\u0062.kmodel", "mqtt.kbridge.json", "dir\\/a.kmeta"), result.references.map { text.substring(it.range.start, it.range.end) })
        assertNull(result.descriptor?.events)
    }

    @Test
    fun `documents that are no descriptor object are invalid`() {
        val syntax = listOf("", "   ", "{\"metamodel\": \"a\", \"model\": \"b\"} trailing", "{\"metamodel\": \"a\", \"model\": \"b\",}")
        syntax.forEach { text ->
            val result = reader.read(path, text)
            assertNull(result.descriptor, text)
            assertTrue(result.diagnostics.isNotEmpty(), text)
            assertTrue(result.diagnostics.all { it.code == DiagnosticCodes.SYNTAX_ERROR || it.code == DiagnosticCodes.LEXER_ERROR }, "$text: ${result.diagnostics}")
        }
        listOf("[]" to "a list", "\"x\"" to "a string").forEach { (text, kind) ->
            val error = reader.read(path, text).diagnostics.single()
            assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, error.code, text)
            assertEquals("The descriptor must be an object, not $kind.", error.message)
        }
    }

    @Test
    fun `numbers must be whole and null is allowed only for nullable keys`() {
        val required = "\"metamodel\": \"a.kmeta\", \"model\": \"a.kmodel\""
        val accepted = reader.read(path, "{$required, \"events\": null, \"tickDelayMs\": null, \"runtime\": {\"image\": null}}")
        assertEquals(emptyList(), accepted.diagnostics)
        assertNull(accepted.descriptor?.events)
        val cases = listOf(
            "{$required, \"tickDelayMs\": 1.5}" to "1.5",
            "{$required, \"eventTtlMs\": 1e3}" to "1e3",
            "{$required, \"tickDelayMs\": 99999999999999999999}" to "99999999999999999999",
            "{$required, \"bridges\": null}" to "null",
            "{$required, \"bridges\": [null]}" to "null",
            "{\"metamodel\": null, \"model\": \"a.kmodel\"}" to "null",
        )
        for ((text, value) in cases) {
            val error = reader.read(path, text).diagnostics.single()
            assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID, error.code, text)
            assertEquals(TextRange.ofLength(text.lastIndexOf(value), value.length), error.range, "${error.message} in $text")
        }
        assertEquals(
            "'tickDelayMs' must be a whole number, not 1.5.",
            reader.read(path, cases[0].first).diagnostics.single().message,
        )
    }

    @Test
    fun `reading never throws on mutations and prefixes of the examples, and its results are well-formed`(reporter: TestReporter) {
        var count = 0
        for (name in DescriptorExamples.names()) {
            val example = DescriptorExamples.text(name)
            val texts = Mutations.random(example, seed = 4711L + name.length, count = 2_000).map { it.text } + Mutations.prefixes(example)
            for (text in texts) {
                val result = reader.read(path, text)
                assertWellFormed(text, result)
                count++
            }
        }
        reporter.publishEntry("descriptor reader robustness", "$count mutated or truncated descriptors read without exception")
    }

    /**
     * Asserts sorted diagnostics, ranges inside [text], an error for every missing descriptor and ordered references.
     */
    private fun assertWellFormed(text: String, result: DescriptorReadResult) {
        assertEquals(result.diagnostics.sortedWith(Diagnostic.ORDER), result.diagnostics, text)
        (result.diagnostics.map { it.range } + result.references.map { it.range }).forEach { range ->
            assertTrue(range.start in 0..range.end && range.end <= text.length, "range $range outside text of length ${text.length}: $text")
        }
        if (result.descriptor == null) {
            assertTrue(result.diagnostics.any { it.severity == Severity.ERROR }, "no descriptor without an error: $text")
            assertEquals(emptyList(), result.references)
        }
        assertEquals(result.references.sortedBy { it.range.start }, result.references)
    }
}
