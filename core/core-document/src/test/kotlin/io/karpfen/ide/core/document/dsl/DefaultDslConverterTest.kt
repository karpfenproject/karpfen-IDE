package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.antlr.tree.KmetaSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KmodelSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dsl-tools adapter on real conversions: values for valid input, located DSL_TOOLS errors otherwise. */
class DefaultDslConverterTest {

    private val converter: DslConverter = DslConverter()

    private fun kmetaIndex(snapshot: TextSnapshot): SyntaxIndex = KmetaSyntaxAnalyzer().analyze(snapshot).index
    private fun kmodelIndex(snapshot: TextSnapshot): SyntaxIndex = KmodelSyntaxAnalyzer().analyze(snapshot).index
    private fun kstatesIndex(snapshot: TextSnapshot): SyntaxIndex = KstatesSyntaxAnalyzer().analyze(snapshot).index

    @Test
    fun `every example context converts through the adapter`() {
        for (context in Examples.contexts()) {
            val metamodelSource = TextSnapshot(context.metamodel!!.text, 0)
            val metamodel = converter.kmeta(metamodelSource, kmetaIndex(metamodelSource))
            assertTrue(metamodel.isSuccess && metamodel.diagnostics.isEmpty(), "$context: ${metamodel.diagnostics}")
            context.events?.let { events ->
                val source = TextSnapshot(events.text, 0)
                val converted = converter.kmeta(source, kmetaIndex(source), metamodel.value!!.types)
                assertTrue(converted.isSuccess, "${events.path}: ${converted.diagnostics}")
            }
            context.models.forEach { model ->
                val source = TextSnapshot(model.text, 0)
                val converted = converter.kmodel(source, kmodelIndex(source), metamodel.value!!)
                assertTrue(converted.isSuccess, "${model.path}: ${converted.diagnostics}")
            }
            context.stateMachines.forEach { machine ->
                val source = TextSnapshot(machine.text, 0)
                val converted = converter.kstates(source, kstatesIndex(source))
                assertNotNull(converted.value, "${machine.path}: ${converted.diagnostics}")
            }
        }
    }

    @Test
    fun `an unknown target type becomes a DSL_TOOLS error at the quoted name`() {
        val text = "type \"Robot\" \"r\" {\n    has(\"position\", \"Vector\")\n}"
        val source = TextSnapshot(text, 0)

        val conversion = converter.kmeta(source, kmetaIndex(source))

        assertNull(conversion.value)
        val diagnostic = conversion.diagnostics.single()
        assertEquals(
            Diagnostic(
                TextRange.ofLength(text.indexOf("Vector"), "Vector".length),
                Severity.ERROR,
                "Unknown target class type 'Vector' for property",
                DiagnosticCodes.DSL_TOOLS_ERROR,
                DiagnosticSource.DSL_TOOLS,
            ),
            diagnostic,
        )
    }

    @Test
    fun `base types resolve references an events metamodel cannot resolve alone`() {
        val domainSource = TextSnapshot("type \"Vector\" \"v\" {\n    prop(\"x\", \"number\")\n}", 0)
        val domain = converter.kmeta(domainSource, kmetaIndex(domainSource)).value!!
        val eventsSource = TextSnapshot("type \"teleport\" \"t\" {\n    has(\"position\", \"Vector\")\n}", 0)

        assertNull(converter.kmeta(eventsSource, kmetaIndex(eventsSource)).value)
        val events = converter.kmeta(eventsSource, kmetaIndex(eventsSource), domain.types)
        assertEquals(setOf("teleport", "Vector"), events.value!!.getClassTypes())
    }

    @Test
    fun `a model with an unknown property is located at the key the message names first`() {
        val metamodelSource = TextSnapshot("type \"Point\" \"p\" {\n    prop(\"x\", \"number\")\n}", 0)
        val metamodel = converter.kmeta(metamodelSource, kmetaIndex(metamodelSource)).value!!
        val text = "make object \"origin\":\"Point\" {\n    prop(\"x\") -> \"0\"\n    prop(\"z\") -> \"1\"\n}"
        val source = TextSnapshot(text, 0)

        val conversion = converter.kmodel(source, kmodelIndex(source), metamodel)

        assertNull(conversion.value)
        val diagnostic = conversion.diagnostics.single()
        assertTrue("'z'" in diagnostic.message, diagnostic.message)
        assertEquals(TextRange.ofLength(text.indexOf("\"z\"") + 1, 1), diagnostic.range, "the offending key, not the object")
    }

    @Test
    fun `a message without names points at the first token`() {
        val text = "// machine\nSTATEMACHINE ATTACHED TO \"T\" {\n STATES { STATE \"a\" { } }\n TRANSITIONS { }\n}"
        val source = TextSnapshot(text, 0)

        val conversion = converter.kstates(source, kstatesIndex(source))

        val diagnostic = conversion.diagnostics.single()
        assertTrue("INITIAL" in diagnostic.message)
        assertEquals(TextRange.ofLength(text.indexOf("STATEMACHINE"), "STATEMACHINE".length), diagnostic.range)
    }

    @Test
    fun `an empty metamodel is an error, not an exception`() {
        val source = TextSnapshot("// nothing yet\n", 0)

        val conversion = converter.kmeta(source, SyntaxIndex.EMPTY)

        assertEquals("KMeta content does not define any types", conversion.diagnostics.single().message)
        assertEquals(TextRange.EMPTY, conversion.diagnostics.single().range)
    }

    @Test
    fun `a custom locator decides the ranges`() {
        val fixed = TextRange(1, 2)
        val custom = DslConverter(DslErrorLocator { _, _, _ -> fixed })

        assertEquals(fixed, custom.kmeta(TextSnapshot("", 0), SyntaxIndex.EMPTY).diagnostics.single().range)
    }
}
