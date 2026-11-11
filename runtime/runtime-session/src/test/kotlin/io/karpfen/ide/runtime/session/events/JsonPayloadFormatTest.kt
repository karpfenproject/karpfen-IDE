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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.runtime.protocol.message.EventPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the checks of JSON payloads as the runtime's converter reads them. It checks every finding code with its range
 * in the payload text, the template and the encoding.
 */
class JsonPayloadFormatTest {

    private val format = JsonPayloadFormat(OrderEvents.schema)

    @Test
    fun `a payload with every member the runtime reads is clean but for the knows member, which builds a new object`() {
        val text = "{\"count\": 2, \"urgent\": true, \"note\": \"fragile\", \"tags\": [\"a\", \"b\"], " +
            "\"destination\": {\"x\": 1, \"y\": 2.5}, \"stops\": [{\"x\": 0, \"y\": 0}], \"crate\": {\"label\": \"c\"}, \"__type__\": \"order\"}"

        val validation = format.validate(text, OrderEvents.order)

        assertEquals(listOf(PayloadDiagnosticCodes.KNOWS_CREATES_OBJECT to "\"crate\""), findings(text))
        assertFalse(validation.hasErrors)
    }

    @Test
    fun `text that is not JSON is a syntax finding at the parser's range`() {
        val text = "{\"count\": }"

        val validation = format.validate(text, OrderEvents.order)

        assertEquals(JsonParser.parse(text).diagnostics.map { it.range }, validation.diagnostics.map { it.range })
        assertTrue(validation.diagnostics.all { it.code == PayloadDiagnosticCodes.JSON_SYNTAX && it.severity == Severity.ERROR })
        assertTrue(validation.hasErrors)
    }

    @Test
    fun `anything but an object is refused`() {
        assertEquals(listOf(PayloadDiagnosticCodes.NOT_OBJECT to "[1, 2]"), findings("[1, 2]"))
        assertEquals(listOf(PayloadDiagnosticCodes.NOT_OBJECT to "\"order\""), findings("\"order\""))
    }

    @Test
    fun `a repeated key is an error at its second occurrence`() {
        val text = "{\"count\": 1, \"count\": 2}"

        val validation = format.validate(text, OrderEvents.order)
        val duplicate = validation.diagnostics.single { it.code == PayloadDiagnosticCodes.DUPLICATE_KEY }

        assertEquals(TextRange(13, 20), duplicate.range)
        assertTrue(validation.hasErrors)
    }

    @Test
    fun `an id must be a string at every level, and a string id is assigned`() {
        assertEquals(listOf(PayloadDiagnosticCodes.ID_NOT_STRING to "5"), findings("{\"__id__\": 5}"))
        assertEquals(listOf(PayloadDiagnosticCodes.ID_ASSIGNED to "\"job-1\""), findings("{\"__id__\": \"job-1\"}"))
        assertEquals(listOf(PayloadDiagnosticCodes.ID_NOT_STRING to "true"), findings("{\"destination\": {\"__id__\": true}}"))
    }

    @Test
    fun `numbers and booleans must be JSON numbers and booleans, also inside nested objects and lists`() {
        assertEquals(listOf(PayloadDiagnosticCodes.NUMBER_EXPECTED to "\"2\""), findings("{\"count\": \"2\"}"))
        assertEquals(listOf(PayloadDiagnosticCodes.NUMBER_EXPECTED to "\"1\""), findings("{\"destination\": {\"x\": \"1\"}}"))
        assertEquals(listOf(PayloadDiagnosticCodes.NUMBER_EXPECTED to "true"), findings("{\"stops\": [{\"y\": true}]}"))
        assertEquals(listOf(PayloadDiagnosticCodes.BOOLEAN_EXPECTED to "1"), findings("{\"urgent\": 1}"))
        assertEquals(listOf(PayloadDiagnosticCodes.BOOLEAN_EXPECTED to "\"yes\""), findings("{\"urgent\": \"yes\"}"))
    }

    @Test
    fun `a list must be an array, and a string takes the text of any other value`() {
        assertEquals(listOf(PayloadDiagnosticCodes.LIST_EXPECTED to "\"a\""), findings("{\"tags\": \"a\"}"))
        assertEquals(listOf(PayloadDiagnosticCodes.TEXT_FROM_VALUE to "1"), findings("{\"tags\": [\"a\", 1]}"))
        assertEquals(listOf(PayloadDiagnosticCodes.TEXT_FROM_VALUE to "5"), findings("{\"note\": 5}"))
    }

    @Test
    fun `a relation must be an object, a list relation an array of objects`() {
        assertEquals(listOf(PayloadDiagnosticCodes.OBJECT_EXPECTED to "5"), findings("{\"destination\": 5}"))
        assertEquals(listOf(PayloadDiagnosticCodes.OBJECT_EXPECTED to "{\"x\": 1}"), findings("{\"stops\": {\"x\": 1}}"))
        assertEquals(listOf(PayloadDiagnosticCodes.OBJECT_EXPECTED to "5"), findings("{\"stops\": [5]}"))
        assertEquals(listOf(PayloadDiagnosticCodes.OBJECT_EXPECTED to "null"), findings("{\"stops\": [null]}"))
    }

    @Test
    fun `an unknown key is a warning, and the type name the runtime writes is not read`() {
        assertEquals(listOf(PayloadDiagnosticCodes.UNKNOWN_KEY to "\"colour\""), findings("{\"colour\": \"red\", \"__type__\": \"order\"}"))
    }

    @Test
    fun `a null member is left out`() {
        assertEquals(listOf(PayloadDiagnosticCodes.MEMBER_OMITTED to "\"note\": null"), findings("{\"note\": null}"))
        assertEquals(listOf(PayloadDiagnosticCodes.MEMBER_OMITTED to "\"destination\": null"), findings("{\"destination\": null}"))
    }

    @Test
    fun `every absent member is reported with the default the runtime fills in, at the object's brace`() {
        val validation = format.validate("{\"count\": 1}", OrderEvents.order)
        val defaulted = validation.diagnostics.filter { it.code == PayloadDiagnosticCodes.MEMBER_DEFAULTED }

        assertEquals(6, defaulted.size)
        assertTrue(defaulted.all { it.range == TextRange(0, 1) && it.severity == Severity.INFO })
        assertEquals(
            listOf("false", "an empty string", "an empty list", "no object", "an empty list", "no object").map { "fills in $it." },
            defaulted.map { it.message.substringAfter("the runtime ") },
        )
        assertFalse(validation.hasErrors)
    }

    @Test
    fun `an event named like a domain type is parsed against it, and an event without a type is never parsed`() {
        val text = "{\"x\": 1, \"y\": 2}"

        assertEquals(listOf(PayloadDiagnosticCodes.DOMAIN_TYPE to text), findings(text, OrderEvents.point))
        assertEquals(listOf(PayloadDiagnosticCodes.UNTYPED_EVENT to text), findings(text, OrderEvents.ghost))
        assertEquals(
            listOf(PayloadDiagnosticCodes.NOT_PARSED to text),
            JsonPayloadFormat(null).validate(text, OrderEvents.order).diagnostics.map { it.code to text.substring(it.range.start, it.range.end) },
        )
    }

    @Test
    fun `the template holds every member the runtime defaults, embedded objects nested, and checks clean`() {
        val template = format.template(OrderEvents.order)

        assertEquals(
            """
            {
              "count": 0,
              "urgent": false,
              "note": "",
              "tags": [],
              "destination": {
                "x": 0,
                "y": 0
              },
              "stops": []
            }
            """.trimIndent(),
            template,
        )
        assertEquals(emptyList(), findings(template))
        assertFalse(format.validate(template, OrderEvents.order).hasErrors)
        assertEquals("{}", format.template(OrderEvents.ping))
        assertEquals("{}", format.template(OrderEvents.ghost))
    }

    @Test
    fun `a JSON payload is sent as written`() {
        assertEquals(EventPayload.Json("{\"count\": 1}"), format.encode("{\"count\": 1}"))
        assertEquals(PayloadKind.JSON, format.kind)
    }

    @Test
    fun `every finding code has the severity of the table`() {
        val errors = setOf(
            "payload-json-syntax", "payload-not-object", "payload-duplicate-key", "payload-id-not-string", "payload-number-expected",
            "payload-boolean-expected", "payload-list-expected", "payload-object-expected", "payload-kmodel-invalid",
            "payload-kmodel-no-object", "event-blank-name",
        )
        val warnings = setOf(
            "payload-unknown-key", "payload-knows-creates-object", "payload-untyped-event", "payload-not-parsed",
            "payload-kmodel-several-objects", "payload-kmodel-type", "event-not-listened", "event-queued-until-start",
            "event-domain-is-attachment",
        )
        val infos = setOf("payload-id-assigned", "payload-domain-type", "payload-member-defaulted", "payload-member-omitted", "payload-text-from-value")

        assertEquals(
            errors.associateWith { Severity.ERROR } + warnings.associateWith { Severity.WARNING } + infos.associateWith { Severity.INFO },
            PayloadDiagnosticCodes.SEVERITIES,
        )
    }

    /**
     * The codes and range texts of the findings on [text] as the payload of [event]. The findings that an absent member
     * gets its default are left out unless [withDefaults] is true.
     */
    private fun findings(text: String, event: InjectableEvent = OrderEvents.order, withDefaults: Boolean = false): List<Pair<String, String>> =
        format.validate(text, event).diagnostics
            .filter { withDefaults || it.code != PayloadDiagnosticCodes.MEMBER_DEFAULTED }
            .map { it.code to text.substring(it.range.start, it.range.end) }
}
