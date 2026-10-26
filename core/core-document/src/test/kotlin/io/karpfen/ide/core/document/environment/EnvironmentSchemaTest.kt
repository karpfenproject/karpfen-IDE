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
package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.document.json.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the descriptor schema and the walk of a descriptor's tree along it. */
class EnvironmentSchemaTest {

    @Test
    fun `the schema names the file key of every role`() {
        assertEquals(
            mapOf(
                DescriptorFileRole.METAMODEL to "metamodel",
                DescriptorFileRole.EVENTS to "events",
                DescriptorFileRole.MODEL to "model",
                DescriptorFileRole.STATE_MACHINE to "stateMachines[].file",
                DescriptorFileRole.BRIDGE to "bridges[]",
            ),
            DescriptorFileRole.entries.associateWith { EnvironmentSchema.keyPathOf(it) },
        )
        assertFailsWith<IllegalArgumentException> { EnvironmentSchema.keyPathOf(DescriptorFileRole.BRIDGE, ValueShape.Record(emptyList())) }
    }

    @Test
    fun `the root object requires the metamodel and the model and documents every key`() {
        val root = EnvironmentSchema.ROOT

        assertEquals(listOf("metamodel", "model"), root.keys.filter { it.isRequired }.map { it.name })
        assertTrue(root.keys.all { it.documentation.isNotBlank() })
        assertEquals(ValueShape.FilePath(DescriptorFileRole.EVENTS), root.key("events")?.shape)
        assertNull(root.key("tickDelay"))
        assertEquals(
            listOf(KarpfenLanguage.KMETA, KarpfenLanguage.KMETA, KarpfenLanguage.KMODEL, KarpfenLanguage.KSTATES, null),
            DescriptorFileRole.entries.map { it.language },
        )
        assertFailsWith<IllegalArgumentException> {
            ValueShape.Record(listOf(root.keys[0], root.keys[0]))
        }
    }

    @Test
    fun `the schema is derived from the typed descriptor, keys, nesting and annotations included`() {
        val root = EnvironmentSchema.ROOT

        assertEquals(
            listOf("metamodel", "events", "model", "stateMachines", "tickDelayMs", "eventTtlMs", "observe", "runtime", "bridges"),
            root.keys.map { it.name },
        )
        assertEquals(
            listOf("events", "tickDelayMs", "eventTtlMs", "observe", "runtime"),
            root.keys.filter { it.isNullable }.map { it.name },
        )
        assertEquals(ValueShape.WholeNumber(listOf(50, 100, 500)), root.key("tickDelayMs")?.shape)
        assertEquals(ValueShape.ListOf(ValueShape.FilePath(DescriptorFileRole.BRIDGE)), root.key("bridges")?.shape)

        val entry = assertIs<ValueShape.Record>(assertIs<ValueShape.ListOf>(root.key("stateMachines")?.shape).item)
        assertEquals(
            listOf(
                DescriptorKey(
                    "file", ValueShape.FilePath(DescriptorFileRole.STATE_MACHINE), isRequired = true, isNullable = false,
                    "The state machine: a .kstates file, relative to this descriptor.",
                ),
                DescriptorKey(
                    "attachedTo", ValueShape.ObjectKey, isRequired = true, isNullable = false,
                    "The key of the model object the state machine runs attached to; its type should be the type the state " +
                        "machine names after ATTACHED TO.",
                ),
            ),
            entry.keys,
        )
        val observe = assertIs<ValueShape.Record>(root.key("observe")?.shape)
        assertEquals(ValueShape.Text(listOf("*")), observe.key("objects")?.shape)
        assertEquals(ValueShape.ListOf(ValueShape.EventDomain), observe.key("domains")?.shape)
        val runtime = assertIs<ValueShape.Record>(root.key("runtime")?.shape)
        assertEquals(ValueShape.Text(), runtime.key("image")?.shape)
        assertEquals(ValueShape.ListOf(ValueShape.Text(listOf("numpy"))), runtime.key("pythonPackages")?.shape)
    }

    @Test
    fun `the walk gives every value its shape, key and location in document order`() {
        val root = JsonParser.parse(DescriptorExamples.text(DescriptorExamples.VALID)).root!!

        val values = shapedValues(root)

        assertEquals(
            listOf(
                "", "metamodel", "events", "model", "stateMachines", "stateMachines[0]", "stateMachines[0].file",
                "stateMachines[0].attachedTo", "tickDelayMs", "eventTtlMs", "observe", "observe.objects", "observe.domains",
                "observe.domains[0]", "runtime", "runtime.pythonPackages", "runtime.pythonPackages[0]", "bridges",
            ),
            values.map { it.location },
        )
        val byLocation = values.associateBy { it.location }
        assertEquals(ValueShape.FilePath(DescriptorFileRole.STATE_MACHINE), byLocation.getValue("stateMachines[0].file").shape)
        assertEquals(ValueShape.ObjectKey, byLocation.getValue("stateMachines[0].attachedTo").shape)
        assertEquals(ValueShape.EventDomain, byLocation.getValue("observe.domains[0]").shape)
        assertTrue(byLocation.getValue("observe.domains[0]").isListItem)
        assertEquals("domains", byLocation.getValue("observe.domains[0]").key?.name)
        assertNull(byLocation.getValue("").key)
    }

    @Test
    fun `values below an unknown key or a value of the wrong kind have no shape`() {
        val root = JsonParser.parse("{\"extra\": {\"file\": \"a\"}, \"stateMachines\": {\"file\": \"b\"}}").root!!

        val values = shapedValues(root).associateBy { it.location }

        assertNull(values.getValue("extra").shape)
        assertNull(values.getValue("extra.file").shape)
        assertTrue(values.getValue("stateMachines").shape is ValueShape.ListOf, "the key is known")
        assertTrue(values.getValue("stateMachines").node is JsonNode.ObjectNode, "but its value is an object")
        assertNull(values.getValue("stateMachines.file").shape, "so nothing below it is known")
    }
}
