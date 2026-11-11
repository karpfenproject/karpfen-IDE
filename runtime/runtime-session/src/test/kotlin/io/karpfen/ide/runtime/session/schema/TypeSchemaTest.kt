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
package io.karpfen.ide.runtime.session.schema

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.ide.runtime.session.examples.DslTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the schema of a metamodel. It holds every type with its members in declaration order, read once into plain
 * values.
 */
class TypeSchemaTest {

    @Test
    fun `the schema lists every type of the metamodel with its properties and relations in declaration order`() {
        val schema = TypeSchema.of(DslTexts.metamodel(METAMODEL))

        assertEquals(listOf("Vector", "Robot"), schema.names)
        assertEquals(
            TypeShape(
                name = "Robot",
                comment = "A robot that knows its neighbours",
                properties = listOf(
                    PropertyShape("log", SimplePropertyType.STRING, isList = true),
                    PropertyShape("speed", SimplePropertyType.NUMBER, isList = false),
                    PropertyShape("busy", SimplePropertyType.BOOLEAN, isList = false),
                ),
                relations = listOf(
                    RelationShape("position", AssociationType.EMBEDDED, isList = false, targetType = "Vector"),
                    RelationShape("trail", AssociationType.EMBEDDED, isList = true, targetType = "Vector"),
                    RelationShape("neighbours", AssociationType.LINK, isList = true, targetType = "Robot"),
                ),
            ),
            schema.type("Robot"),
        )
        assertEquals(PropertyShape("x", SimplePropertyType.NUMBER, false), schema.type("Vector")?.property("x"))
        assertEquals("neighbours", schema.type("Robot")?.relation("neighbours")?.key)
        assertNull(schema.type("Robot")?.property("position"))
        assertNull(schema.type("Robot")?.relation("speed"))
        assertNull(schema.type("Unknown"))
    }

    @Test
    fun `a blank description is no comment, and the comment of a type is trimmed`() {
        val schema = TypeSchema.of(DslTexts.metamodel("type \"A\" \"\" {\n}\ntype \"B\" \"  spaced  \" {\n}\n"))

        assertNull(schema.type("A")?.comment)
        assertEquals("spaced", schema.type("B")?.comment)
    }

    @Test
    fun `a name that several types share resolves to the first, as the runtime's lookup by name does`() {
        val first = TypeShape("Event", "from the events file", emptyList(), emptyList())
        val second = TypeShape("Event", "from the domain", emptyList(), emptyList())
        val schema = TypeSchema(listOf(first, TypeShape("Other", null, emptyList(), emptyList()), second))

        assertEquals(first, schema.type("Event"))
        assertEquals(listOf("Event", "Other"), schema.names)
        assertEquals(listOf(first, TypeShape("Other", null, emptyList(), emptyList()), second), schema.types)
    }

    @Test
    fun `schemas of the same types are equal`() {
        val metamodel = DslTexts.metamodel(METAMODEL)

        assertEquals(TypeSchema.of(metamodel), TypeSchema.of(DslTexts.metamodel(METAMODEL)))
        assertEquals(TypeSchema.of(metamodel).hashCode(), TypeSchema(TypeSchema.of(metamodel).types).hashCode())
    }

    private companion object {
        /** A metamodel with every kind of member. */
        const val METAMODEL = """type "Vector" "A point" {
    prop("x", "number")
    prop("y", "number")
}
type "Robot" "  A robot that knows its neighbours " {
    prop("log", list("string"))
    prop("speed", "number")
    prop("busy", "boolean")
    has("position", "Vector")
    has("trail", list("Vector"))
    knows("neighbours", list("Robot"))
}
"""
    }
}
