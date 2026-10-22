package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.reader.TextReader
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dsl-tools adapter on real reads: complete models without findings for the examples, positioned DSL_TOOLS
 * diagnostics with the codes of dsl-tools otherwise, partial models next to them, and a failing reader reported
 * instead of thrown.
 */
class DefaultDslReaderTest {

    private val reader: DslReader = DslReader()

    private val point = "type \"Point\" \"p\" {\n    prop(\"x\", \"number\")\n}\n"

    @Test
    fun `every example context reads without findings`() {
        for (context in Examples.contexts()) {
            val metamodel = reader.kmeta(TextSnapshot(context.metamodel!!.text, 0))
            assertEquals(emptyList(), metamodel.diagnostics, "$context")
            val domain = assertNotNull(metamodel.model)
            context.events?.let { events ->
                val read = reader.kmeta(TextSnapshot(events.text, 0), domain.types)
                assertEquals(emptyList(), read.diagnostics, events.path)
                assertNotNull(read.model, events.path)
            }
            context.models.forEach { model ->
                val read = reader.kmodel(TextSnapshot(model.text, 0), domain)
                assertEquals(emptyList(), read.diagnostics, model.path)
                assertNotNull(read.model, model.path)
            }
            context.stateMachines.forEach { machine ->
                val read = reader.kstates(TextSnapshot(machine.text, 0))
                assertEquals(emptyList(), read.diagnostics, machine.path)
                assertNotNull(read.model, machine.path)
            }
        }
    }

    @Test
    fun `a finding becomes a DSL_TOOLS diagnostic with the code, severity, message and range of dsl-tools`() {
        val text = "type \"Robot\" \"r\" {\n    has(\"position\", \"Vector\")\n}"

        val read = reader.kmeta(TextSnapshot(text, 0))

        val expected = Diagnostic(
            range = TextRange.ofLength(text.indexOf("Vector"), "Vector".length),
            severity = Severity.ERROR,
            message = "Unknown class type 'Vector' of has 'position' in type 'Robot'",
            code = "kmeta.unknown-target-type",
            source = DiagnosticSource.DSL_TOOLS,
        )
        assertEquals(listOf(expected), read.diagnostics)
        assertTrue(read.hasErrors)
    }

    @Test
    fun `a text with errors keeps its partial model`() {
        val text = "type \"Robot\" \"r\" {\n    prop(\"speed\", \"nmber\")\n    prop(\"name\", \"string\")\n}"

        val read = reader.kmeta(TextSnapshot(text, 0))

        assertEquals(listOf("kmeta.unknown-property-type"), read.diagnostics.map { it.code })
        val robot = assertNotNull(read.model).rootClass
        assertEquals(listOf("name"), robot.simpleProperties.map { it.key }, "the property of an unknown type is left out")
    }

    @Test
    fun `a warning stays a warning and leaves the model complete`() {
        val metamodel = assertNotNull(
            reader.kmeta(TextSnapshot("$point\ntype \"Line\" \"l\" {\n    has(\"start\", \"Point\")\n}\n", 0)).model,
        )
        val text = "make object \"line\":\"Line\" {\n    has(\"start\") -> make object \"other\":\"Line\" {\n    }\n}"

        val read = reader.kmodel(TextSnapshot(text, 0), metamodel)

        val warning = read.diagnostics.single()
        assertEquals("kmodel.embedded-type-mismatch" to Severity.WARNING, warning.code to warning.severity)
        assertFalse(read.hasErrors)
        assertNotNull(read.model)
    }

    @Test
    fun `base types resolve references an events metamodel cannot resolve alone`() {
        val domain = assertNotNull(reader.kmeta(TextSnapshot("type \"Vector\" \"v\" {\n    prop(\"x\", \"number\")\n}", 0)).model)
        val events = TextSnapshot("type \"teleport\" \"t\" {\n    has(\"position\", \"Vector\")\n}", 0)

        assertTrue(reader.kmeta(events).hasErrors)
        val read = reader.kmeta(events, domain.types)
        assertEquals(emptyList(), read.diagnostics)
        assertEquals(setOf("teleport", "Vector"), read.model!!.getClassTypes())
    }

    @Test
    fun `a metamodel without types yields no model and an error at the start of the text`() {
        val read = reader.kmeta(TextSnapshot("// nothing yet\n", 0))

        assertNull(read.model)
        val error = read.diagnostics.single()
        assertEquals("kmeta.no-types" to TextRange.EMPTY, error.code to error.range)
    }

    @Test
    fun `offsets count UTF-16 chars like the IDE, also behind characters outside the BMP`() {
        val text = "// 😀 robots\ntype \"Robot\" \"🤖\" {\n    has(\"position\", \"Vector\")\n}"

        val range = reader.kmeta(TextSnapshot(text, 0)).diagnostics.single().range

        assertEquals("Vector", text.substring(range.start, range.end))
    }

    @Test
    fun `a reader that throws is reported as a reader failure at the start of the text`() {
        val failing = TextReader<Any> { throw NotImplementedError("not yet") }

        val read = DefaultDslReader().read(TextSnapshot("text", 3), failing)

        assertNull(read.model)
        val message = "The text could not be read: ${NotImplementedError("not yet")}"
        assertEquals(
            Diagnostic(TextRange.EMPTY, Severity.ERROR, message, "reader.failure", DiagnosticSource.DSL_TOOLS),
            read.diagnostics.single(),
        )
    }

    @Test
    fun `a stack overflow is reported, other fatal errors are not caught`() {
        val overflowing = TextReader<Any> { throw StackOverflowError() }
        val exhausted = TextReader<Any> { throw OutOfMemoryError("test") }

        assertEquals("reader.failure", DefaultDslReader().read(TextSnapshot("", 0), overflowing).diagnostics.single().code)
        assertFailsWith<OutOfMemoryError> { DefaultDslReader().read(TextSnapshot("", 0), exhausted) }
    }

    @Test
    fun `a read without a model must report an error`() {
        assertFailsWith<IllegalArgumentException> { ReadResult<Any>(null, emptyList()) }
    }
}
