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

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.examples.DslTexts
import io.karpfen.ide.runtime.session.schema.TypeSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers payload forms. It checks the fields of a payload type from the event metamodel and nested forms down to a
 * depth limit. It also checks the JSON that a filled-in draft becomes, which the JSON format then checks like typed
 * text.
 */
class PayloadFormTest {

    @Test
    fun `the delivery drone's assignDelivery form has its scalars and the embedded destination point`() {
        val schema = eventSchema("delivery_drone_example", "delivery_drone.kmeta")

        val form = PayloadForm.of("assignDelivery", schema)
        val destination = form.fields.last() as PayloadField.Nested

        assertEquals("assignDelivery", form.typeName)
        assertEquals(
            listOf(PayloadField.Scalar("packageId", SimplePropertyType.STRING, false), PayloadField.Scalar("priority", SimplePropertyType.NUMBER, false)),
            form.fields.take(2),
        )
        assertEquals(listOf("destination", "GeoPoint", AssociationType.EMBEDDED, false), listOf(destination.key, destination.typeName, destination.association, destination.isList))
        assertEquals(
            PayloadForm("GeoPoint", listOf(PayloadField.Scalar("x", SimplePropertyType.NUMBER, false), PayloadField.Scalar("y", SimplePropertyType.NUMBER, false))),
            destination.form(),
        )
    }

    @Test
    fun `the single robot's teleport form embeds a domain vector`() {
        val schema = eventSchema("single_robot_example", "cleaning_robot.kmeta")

        val position = PayloadForm.of("teleport", schema).fields.single() as PayloadField.Nested

        assertEquals("Vector", position.typeName)
        assertEquals(listOf("x", "y"), position.form().fields.map { it.key })
    }

    @Test
    fun `nested forms stop at the depth limit, so a type that refers to itself stays finite`() {
        val schema = TypeSchema.of(DslTexts.metamodel(SynthesizedModels.text(SynthesizedModel.RING, "ring.kmeta")))

        val node = PayloadForm.of("Node", schema, depthLimit = 2)
        val next = node.fields.last() as PayloadField.Nested
        val deeper = next.form().fields.last() as PayloadField.Nested

        assertEquals(listOf("visits", "next"), node.fields.map { it.key })
        assertEquals(AssociationType.LINK, next.association)
        assertEquals(listOf("visits", "next"), next.form().fields.map { it.key })
        assertEquals(PayloadForm("Node", emptyList()), deeper.form())
        assertFailsWith<IllegalArgumentException> { PayloadForm.of("Node", schema, depthLimit = 0) }
    }

    @Test
    fun `a type the schema does not know has a form without fields`() {
        assertEquals(PayloadForm("ghost", emptyList()), PayloadForm.of("ghost", OrderEvents.schema))
    }

    @Test
    fun `a draft becomes compact JSON in form order that the JSON format accepts`() {
        val schema = eventSchema("delivery_drone_example", "delivery_drone.kmeta")
        val form = PayloadForm.of("assignDelivery", schema)
        val draft = PayloadDraft(
            mapOf(
                listOf("destination", "y") to PayloadDraftValue.Single(PayloadScalar.Number(8.0)),
                listOf("priority") to PayloadDraftValue.Single(PayloadScalar.Number(2.0)),
                listOf("destination", "x") to PayloadDraftValue.Single(PayloadScalar.Number(12.0)),
                listOf("packageId") to PayloadDraftValue.Single(PayloadScalar.Text("PKG-42")),
                listOf("unknown") to PayloadDraftValue.Single(PayloadScalar.Text("ignored")),
            ),
        )
        val event = InjectableEvent("control", "assignDelivery", true, "assignDelivery", PayloadTypeOrigin.EVENT_TYPE, null, emptyList(), emptyList())

        val json = form.toJson(draft)

        assertEquals("{\"packageId\":\"PKG-42\",\"priority\":2,\"destination\":{\"x\":12,\"y\":8}}", json)
        assertEquals(emptyList(), JsonPayloadFormat(schema).validate(json, event).diagnostics)
    }

    @Test
    fun `lists become arrays, list relations take their entries by index, and absent fields are left out`() {
        val form = PayloadForm.of("order", OrderEvents.schema)
        val draft = PayloadDraft(
            mapOf(
                listOf("urgent") to PayloadDraftValue.Single(PayloadScalar.Flag(true)),
                listOf("tags") to PayloadDraftValue.Many(listOf(PayloadScalar.Text("a \"quoted\" tag"), PayloadScalar.Text("b"))),
                listOf("stops", "1", "x") to PayloadDraftValue.Single(PayloadScalar.Number(1.5)),
                listOf("stops", "0", "y") to PayloadDraftValue.Single(PayloadScalar.Number(-0.0)),
                listOf("crate", "label") to PayloadDraftValue.Single(PayloadScalar.Text("c")),
            ),
        )

        assertEquals(
            "{\"urgent\":true,\"tags\":[\"a \\\"quoted\\\" tag\",\"b\"],\"stops\":[{\"y\":0},{\"x\":1.5}],\"crate\":{\"label\":\"c\"}}",
            form.toJson(draft),
        )
        assertEquals("{}", form.toJson(PayloadDraft.EMPTY))
    }

    @Test
    fun `a number that is not finite has no JSON form, is written as text and refused by the JSON checks`() {
        val form = PayloadForm.of("order", OrderEvents.schema)
        val draft = PayloadDraft(mapOf(listOf("count") to PayloadDraftValue.Single(PayloadScalar.Number(Double.NaN))))

        val json = form.toJson(draft)
        val validation = JsonPayloadFormat(OrderEvents.schema).validate(json, OrderEvents.order)

        assertEquals("{\"count\":\"NaN\"}", json)
        assertTrue(PayloadDiagnosticCodes.NUMBER_EXPECTED in validation.codes)
        assertTrue(validation.hasErrors)
        assertEquals("{\"count\":1.0E20}", form.toJson(PayloadDraft(mapOf(listOf("count") to PayloadDraftValue.Single(PayloadScalar.Number(1.0E20))))))
        assertFalse(JsonPayloadFormat(OrderEvents.schema).validate("{\"count\":1.0E20}", OrderEvents.order).hasErrors)
    }

    /**
     * The schema of the event metamodel of the runtime's example in [directory], whose domain metamodel is
     * [metamodelFile].
     */
    private fun eventSchema(directory: String, metamodelFile: String): TypeSchema {
        val domain = DslTexts.metamodel(Examples.file("karpfen-runtime/$directory/$metamodelFile").text)
        return TypeSchema.of(DslTexts.metamodel(Examples.file("karpfen-runtime/$directory/EVENTS.kmeta").text, domain.types))
    }
}
