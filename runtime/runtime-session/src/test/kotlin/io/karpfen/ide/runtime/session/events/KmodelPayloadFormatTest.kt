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
package io.karpfen.ide.runtime.session.events

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.protocol.message.EventPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the checks of KModel payloads as the runtime's single-object parser reads them: syntax and reader errors, a
 * snippet without an object or with several, an object of another type than the event, the event metamodel read once
 * from the uploaded texts, the template and the encoding.
 */
class KmodelPayloadFormatTest {

    private val reader = CountingReader(DslReader())

    private val format = format(OrderEvents.sources)

    @Test
    fun `an object of the event's type that fits the event definitions is clean`() {
        val text = "make object \"o\":\"order\" {\n    prop(\"count\") -> \"2\"\n    has(\"destination\") -> make object \"d\":\"Point\" {\n" +
            "        prop(\"x\") -> \"1\"\n    }\n}"

        assertEquals(emptyList(), format.validate(text, OrderEvents.order).diagnostics)
    }

    @Test
    fun `an object of another type is a warning at its key, because the runtime uses the snippet's type`() {
        val text = "make object \"p\":\"Point\" { prop(\"x\") -> \"1\" }"

        val validation = format.validate(text, OrderEvents.order)

        assertEquals(listOf(PayloadDiagnosticCodes.KMODEL_TYPE), validation.codes)
        assertEquals("p", text.substring(validation.diagnostics.single().range.start, validation.diagnostics.single().range.end))
        assertFalse(validation.hasErrors)
    }

    @Test
    fun `further root objects are a warning on them, and the first is checked as the runtime takes it`() {
        val first = "make object \"o\":\"order\" { prop(\"count\") -> \"2\" }"
        val others = "make object \"o2\":\"order\" { }\nmake object \"o3\":\"order\" { prop(\"colour\") -> \"red\" }"
        val text = "$first\n$others"

        val validation = format.validate(text, OrderEvents.order)

        assertEquals(listOf(PayloadDiagnosticCodes.KMODEL_SEVERAL_OBJECTS), validation.codes)
        assertEquals(others, text.substring(validation.diagnostics.single().range.start, validation.diagnostics.single().range.end))
        assertFalse(validation.hasErrors)
    }

    @Test
    fun `a blank snippet holds no object`() {
        listOf("", "  \n ").forEach { text ->
            val validation = format.validate(text, OrderEvents.order)
            assertEquals(listOf(PayloadDiagnosticCodes.KMODEL_NO_OBJECT), validation.codes)
            assertTrue(validation.hasErrors)
        }
    }

    @Test
    fun `syntax errors make the snippet invalid and are reported with their own findings`() {
        listOf("make object \"o\":\"order\" {", "make object \"o\":\"order\" { } }", "make object \"o\":\"order\" { }\nmake objec").forEach { text ->
            val validation = format.validate(text, OrderEvents.order)
            assertEquals(PayloadDiagnosticCodes.KMODEL_INVALID, validation.codes.first(), text)
            assertTrue(validation.diagnostics.drop(1).isNotEmpty(), text)
            assertTrue(validation.diagnostics.drop(1).all { it.source == DiagnosticSource.PARSER || it.source == DiagnosticSource.LEXER }, text)
            assertTrue(validation.hasErrors, text)
        }
    }

    @Test
    fun `a snippet that does not fit the event definitions is invalid with the reader's findings`() {
        val text = "make object \"o\":\"order\" { prop(\"colour\") -> \"red\" }"

        val validation = format.validate(text, OrderEvents.order)

        assertEquals(PayloadDiagnosticCodes.KMODEL_INVALID, validation.codes.first())
        assertTrue(validation.diagnostics.drop(1).any { it.source == DiagnosticSource.DSL_TOOLS && it.severity == Severity.ERROR })
        assertTrue(validation.hasErrors)
    }

    @Test
    fun `without event definitions payloads are never parsed`() {
        val validation = format(EventSources(TextSnapshot(OrderEvents.DOMAIN_TEXT, 0), null)).validate("make object \"o\":\"order\" { }", OrderEvents.order)

        assertEquals(listOf(PayloadDiagnosticCodes.NOT_PARSED), validation.codes)
        assertEquals(0, reader.metamodelReads)
    }

    @Test
    fun `event definitions that cannot be read make every snippet invalid`() {
        val broken = format(EventSources(TextSnapshot(OrderEvents.DOMAIN_TEXT, 0), TextSnapshot("type \"order\" \"\" {\n    has(\"to\", \"Nowhere\")\n}\n", 0)))

        val validation = broken.validate("make object \"o\":\"order\" { }", OrderEvents.order)

        assertEquals(PayloadDiagnosticCodes.KMODEL_INVALID, validation.codes.first())
        assertTrue(validation.diagnostics.first().message.contains("event definitions"))
    }

    @Test
    fun `the event metamodel is read once, on the first check, from the domain metamodel and the events file`() {
        assertEquals(0, reader.metamodelReads)

        format.validate("make object \"o\":\"order\" { }", OrderEvents.order)
        format.validate("make object \"p\":\"ping\" { }", OrderEvents.ping)

        assertEquals(2, reader.metamodelReads)
        assertEquals(listOf(emptyList(), listOf("Point", "Crate")), reader.baseTypeNames)
        assertEquals(2, reader.modelReads)
    }

    @Test
    fun `the template is one object of the payload type with its single members at their defaults, and checks clean`() {
        val template = format.template(OrderEvents.order)

        assertEquals(
            """
            make object "payload":"order" {
                prop("count") -> "0.0"
                prop("urgent") -> "false"
                prop("note") -> ""
                has("destination") -> make object "payload-destination":"Point" {
                    prop("x") -> "0.0"
                    prop("y") -> "0.0"
                }
            }
            """.trimIndent(),
            template,
        )
        assertEquals(emptyList(), format.validate(template, OrderEvents.order).diagnostics)
        assertEquals("make object \"payload\":\"ping\" { }", format.template(OrderEvents.ping))
        assertEquals("make object \"payload\":\"ghost\" { }", format.template(OrderEvents.ghost))
    }

    @Test
    fun `a KModel payload is sent as written`() {
        assertEquals(EventPayload.Kmodel("make object \"o\":\"order\" { }"), format.encode("make object \"o\":\"order\" { }"))
        assertEquals(PayloadKind.KMODEL, format.kind)
    }

    /** The format over [sources] and the counting reader. */
    private fun format(sources: EventSources): KmodelPayloadFormat =
        KmodelPayloadFormat(sources, OrderEvents.schema, defaultLanguageRegistry().support(KarpfenLanguage.KMODEL).parser, reader)

    /**
     * A reader that counts what it reads and passes every read on to [delegate].
     *
     * @param delegate the reader that reads.
     */
    private class CountingReader(private val delegate: DslReader) : DslReader {
        /** The metamodels read so far. */
        var metamodelReads = 0

        /** The models read so far. */
        var modelReads = 0

        /** The names of the base types of every metamodel read, in order. */
        val baseTypeNames = ArrayList<List<String>>()

        /** Counts and reads the metamodel. */
        override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>): ReadResult<Metamodel> {
            metamodelReads++
            baseTypeNames += baseTypes.map { it.name }
            return delegate.kmeta(source, baseTypes)
        }

        /** Counts and reads the model. */
        override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> {
            modelReads++
            return delegate.kmodel(source, metamodel)
        }

        /** Reads the state machine. */
        override fun kstates(source: TextSnapshot): ReadResult<StateMachine> = delegate.kstates(source)
    }
}
