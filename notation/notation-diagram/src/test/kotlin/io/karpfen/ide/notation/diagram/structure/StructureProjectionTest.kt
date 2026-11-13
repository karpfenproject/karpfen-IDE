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
package io.karpfen.ide.notation.diagram.structure

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.LampStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests what the class diagram of a metamodel and the object diagram of a model hold. */
class StructureProjectionTest {

    private val types = LampStructure.typesOf(LampStructure.METAMODEL)
    private val objects = LampStructure.objectsOf(LampStructure.MODEL)

    @Test
    fun `every type is a box with its simple properties as rows`() {
        assertEquals(StructureKind.TYPES, types.kind)
        assertEquals(listOf("Bulb", "Switch", "Lamp", "Room"), types.nodes.map { it.id })
        assertEquals(listOf("Bulb", "Switch", "Lamp", "Room"), types.nodes.map { it.title })
        assertEquals(listOf("maxWatts: number", "lit: boolean"), types.node("Bulb")?.rows)
        assertEquals(listOf("presses: list(string)"), types.node("Switch")?.rows)
    }

    @Test
    fun `a relation to a declared type is a link, and one to an unknown type is a row`() {
        assertEquals(listOf("mode: string", "shade: Shade"), types.node("Lamp")?.rows)
        assertEquals(
            listOf(
                Triple("has:Lamp.bulb", LinkKind.CONTAINMENT, "Lamp" to "Bulb"),
                Triple("has:Lamp.spare", LinkKind.CONTAINMENT, "Lamp" to "Bulb"),
                Triple("knows:Lamp.control", LinkKind.REFERENCE, "Lamp" to "Switch"),
                Triple("has:Room.lamps", LinkKind.CONTAINMENT, "Room" to "Lamp"),
                Triple("has:Room.switch", LinkKind.CONTAINMENT, "Room" to "Switch"),
            ),
            types.links.map { Triple(it.id, it.kind, it.source to it.target) },
        )
        assertEquals(listOf("bulb", "spare", "control", "lamps [*]", "switch"), types.links.map { it.label })
    }

    @Test
    fun `the name range of a type is its name in the text`() {
        val name = LampStructure.METAMODEL.indexOf("Lamp")
        assertEquals(TextRange(name, name + "Lamp".length), types.node("Lamp")?.nameRange)
    }

    @Test
    fun `a type that is declared twice gives two boxes, and relations lead to the first`() {
        val twice = LampStructure.typesOf(
            """
            type "A" "first" { }
            type "A" "second" { has("other", "A") }
            """.trimIndent(),
        )
        assertEquals(listOf("A", "A#2"), twice.nodes.map { it.id })
        assertEquals(listOf("A#2" to "A"), twice.links.map { it.source to it.target })
    }

    @Test
    fun `every object is a box with its key, its type and the values of its properties`() {
        assertEquals(StructureKind.OBJECTS, objects.kind)
        assertEquals(listOf("living room", "lamp", "bulb", ":Bulb", "wallSwitch"), objects.nodes.map { it.id })
        assertEquals(
            listOf("living room : Room", "lamp : Lamp", "bulb : Bulb", ": Bulb", "wallSwitch : Switch"),
            objects.nodes.map { it.title },
        )
        assertEquals(listOf("maxWatts = 40"), objects.node("bulb")?.rows)
    }

    @Test
    fun `has leads to the object it makes, knows to the object with the key, and an unknown key is a row`() {
        assertEquals(listOf("mode = off", "fuse = fuseBox"), objects.node("lamp")?.rows)
        assertEquals(
            listOf(
                Triple("has:living room.lamps", LinkKind.CONTAINMENT, "living room" to "lamp"),
                Triple("has:lamp.bulb", LinkKind.CONTAINMENT, "lamp" to "bulb"),
                Triple("has:lamp.spare", LinkKind.CONTAINMENT, "lamp" to ":Bulb"),
                Triple("knows:lamp.control", LinkKind.REFERENCE, "lamp" to "wallSwitch"),
                Triple("has:living room.switch", LinkKind.CONTAINMENT, "living room" to "wallSwitch"),
            ),
            objects.links.map { Triple(it.id, it.kind, it.source to it.target) },
        )
    }

    @Test
    fun `a long value is cut`() {
        val long = LampStructure.objectsOf("""make object "a":"A" { prop("note") -> "${"x".repeat(80)}" }""")
        val row = long.nodes.single().rows.single()
        assertTrue(row.length <= 48 && row.endsWith("…"), row)
    }

    @Test
    fun `a text without types gives a diagram without boxes`() {
        assertEquals(emptyList(), LampStructure.typesOf("").nodes)
        assertEquals(emptyList(), LampStructure.typesOf("// nothing yet").links)
    }
}
