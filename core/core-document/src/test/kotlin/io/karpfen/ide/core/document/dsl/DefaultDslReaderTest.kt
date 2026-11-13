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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.TextReader
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.KstatesFeature
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
    fun `a construct of a feature that is switched off is an error at its keyword and stays in the model`() {
        val text = "STATEMACHINE ATTACHED TO \"Lamp\" {\n    STATES {\n        INITIAL DEEP HISTORY STATE \"on\" {\n" +
            "        }\n    }\n    TRANSITIONS {\n    }\n}\n"

        val read = reader.kstates(TextSnapshot(text, 0), KstatesFeature.ALL - KstatesFeature.HISTORY)

        val expected = Diagnostic(
            range = TextRange.ofLength(text.indexOf("DEEP"), "DEEP".length),
            severity = Severity.ERROR,
            message = "Histories are switched off, so this history is not allowed. ${ReadResults.FEATURE_SWITCH}",
            code = "kstates.feature-off",
            source = DiagnosticSource.DSL_TOOLS,
        )
        assertEquals(listOf(expected), read.diagnostics)
        assertEquals("on", assertNotNull(read.model).states.single().name)
        assertEquals(emptyList(), reader.kstates(TextSnapshot(text, 0)).diagnostics, "every feature is on by default")
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
    fun `a reader that throws is reported as a reader failure at the start of the text, in every language`() {
        val failing = readingWith { throw NotImplementedError("not yet") }
        val metamodel = assertNotNull(reader.kmeta(TextSnapshot(point, 0)).model)

        val reads = listOf(
            failing.kmeta(TextSnapshot("text", 3)),
            failing.kmodel(TextSnapshot("text", 3), metamodel),
            failing.kstates(TextSnapshot("text", 3)),
        )

        val message = "The text could not be read: ${NotImplementedError("not yet")}"
        val failure = Diagnostic(TextRange.EMPTY, Severity.ERROR, message, "reader.failure", DiagnosticSource.DSL_TOOLS)
        reads.forEach { read ->
            assertNull(read.model)
            assertEquals(failure, read.diagnostics.single())
        }
    }

    @Test
    fun `a stack overflow is reported, other fatal errors are not caught`() {
        val overflowing = readingWith { throw StackOverflowError() }
        val exhausted = readingWith { throw OutOfMemoryError("test") }

        assertEquals("reader.failure", overflowing.kstates(TextSnapshot("", 0)).diagnostics.single().code)
        assertFailsWith<OutOfMemoryError> { exhausted.kstates(TextSnapshot("", 0)) }
    }

    @Test
    fun `each reader is created over the base types, the metamodel or the features of its read`() {
        val domain = assertNotNull(reader.kmeta(TextSnapshot(point, 0)).model)
        val baseTypes = mutableListOf<List<ClassType>>()
        val metamodels = mutableListOf<Metamodel>()
        val featureSets = mutableListOf<Set<KstatesFeature>>()
        val recording = DefaultDslReader(
            kmetaReader = { types -> baseTypes += types; KmetaReader(types) },
            kmodelReader = { metamodel -> metamodels += metamodel; KmodelReader(metamodel) },
            kstatesReader = { features -> featureSets += features; KstatesReader(features) },
        )

        recording.kmeta(TextSnapshot("type \"move\" \"m\" {\n    has(\"to\", \"Point\")\n}\n", 0), domain.types)
        recording.kmodel(TextSnapshot("make object \"p\":\"Point\" {\n}\n", 0), domain)

        assertEquals<List<List<ClassType>>>(listOf(domain.types), baseTypes)
        assertEquals<List<Metamodel>>(listOf(domain), metamodels)

        val history = setOf(KstatesFeature.HISTORY)
        recording.kstates(TextSnapshot("", 0))
        recording.kstates(TextSnapshot("", 0), history)
        assertEquals(listOf(KstatesFeature.ALL, history), featureSets)
    }

    /** A [DefaultDslReader] whose readers of every language read with [read]. */
    private fun readingWith(read: (String) -> Nothing): DefaultDslReader {
        val failing = TextReader<Nothing> { text -> read(text) }
        return DefaultDslReader(kmetaReader = { failing }, kmodelReader = { failing }, kstatesReader = { failing })
    }

    @Test
    fun `a read without a model must report an error`() {
        assertFailsWith<IllegalArgumentException> { ReadResult<Any>(null, emptyList()) }
    }
}
