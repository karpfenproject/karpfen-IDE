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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.ide.notation.diagram.LampStructure
import io.karpfen.ide.notation.diagram.edit.StructureTexts.applied
import io.karpfen.ide.notation.diagram.edit.StructureTexts.metamodelOf
import io.karpfen.ide.notation.diagram.edit.StructureTexts.readTypes
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the changes a class diagram makes to its metamodel. Every change is applied to the text, and the new text is
 * read again: by the parser of the editor, which must find no syntax error and gives the diagram the change was meant
 * to give, and by the reader of dsl-tools where that shows what the change means in the language.
 */
class TypeEditsTest {

    private val meta = LampStructure.METAMODEL
    private val types = LampStructure.typesOf(meta)

    private val clean = CLEAN
    private val cleanTypes = readTypes(CLEAN)

    @Test
    fun `a new type becomes the first type, so the root type stays the last`() {
        val change = assertNotNull(TypeEdits.addType(meta, types, "Shade", at = DiagramPoint(42f, 58f)))

        val after = readTypes(applied(change, meta))
        assertEquals(listOf("Shade", "Bulb", "Switch", "Lamp", "Room"), after.nodes.map { it.id })
        assertEquals(mapOf("Shade" to DiagramPoint(40f, 60f)), assertNotNull(change.layout)(VisLayout()).boxes)
        assertTrue(after.links.any { it.id == "has:Lamp.shade" }, "the relation to the type that was missing is a link now")
        assertNull(TypeEdits.addType(meta, types, "Lamp"), "the name is taken")
        assertEquals(listOf("Type"), readTypes(applied(TypeEdits.addType("", readTypes(""), "Type"), "")).nodes.map { it.id })
    }

    @Test
    fun `renaming a type renames it in the relations that lead to it and keeps its place and its lines`() {
        val change = assertNotNull(TypeEdits.rename(meta, types, "Bulb", "Light"))

        val after = readTypes(applied(change, meta))
        assertEquals(listOf("Light", "Switch", "Lamp", "Room"), after.nodes.map { it.id })
        assertEquals(listOf("Light", "Light"), after.links.filter { it.source == "Lamp" && it.kind == LinkKind.CONTAINMENT }.map { it.target })
        val vis = VisLayout(
            boxes = mapOf("Bulb" to DiagramPoint(10f, 20f)),
            waypoints = mapOf("has:Lamp.bulb" to listOf(DiagramPoint(1f, 2f))),
        )
        assertEquals(vis.copy(boxes = mapOf("Light" to DiagramPoint(10f, 20f))), assertNotNull(change.layout)(vis))

        val source = assertNotNull(TypeEdits.rename(meta, types, "Lamp", "Torch"))
        assertEquals(mapOf("has:Torch.bulb" to listOf(DiagramPoint(1f, 2f))), assertNotNull(source.layout)(vis).waypoints)
        assertNull(TypeEdits.rename(meta, types, "Bulb", "Bulb"), "the name is the same")
    }

    @Test
    fun `a type may be renamed to a taken or an empty name, which dsl-tools reports`() {
        val taken = applied(TypeEdits.rename(meta, types, "Bulb", "Lamp"), meta)
        assertEquals(listOf("Lamp", "Switch", "Lamp#2", "Room"), readTypes(taken).nodes.map { it.id })
        assertNull(assertNotNull(TypeEdits.rename(meta, types, "Bulb", "Lamp")).layout, "the box is placed anew")

        val empty = applied(TypeEdits.rename(meta, types, "Switch", " "), meta)
        assertTrue("type \"\" \"A wall switch\" {" in empty, empty)
        assertTrue("knows(\"control\", \"\")" in empty, empty)
    }

    @Test
    fun `a type gets a property and relations, and loses them again`() {
        val withProperty = readTypes(applied(TypeEdits.addProperty(meta, types, "Bulb", "colour"), meta))
        assertEquals(listOf("maxWatts: number", "lit: boolean", "colour: string"), withProperty.node("Bulb")?.rows)

        val related = readTypes(applied(TypeEdits.addRelation(meta, types, "Room", LinkKind.REFERENCE, "Bulb"), meta))
        assertTrue(related.links.any { it.id == "knows:Room.bulb" && it.target == "Bulb" })
        val second = readTypes(applied(TypeEdits.addRelation(meta, types, "Lamp", LinkKind.CONTAINMENT, "Bulb"), meta))
        assertTrue(second.links.any { it.id == "has:Lamp.bulb2" }, "the name bulb is taken in Lamp: ${second.links.map { it.id }}")
        val itself = readTypes(applied(TypeEdits.addRelation(meta, types, "Lamp", LinkKind.REFERENCE, "Lamp"), meta))
        assertTrue(itself.links.any { it.id == "knows:Lamp.lamp" && it.target == "Lamp" }, "a type may know itself")

        val unlinked = readTypes(applied(TypeEdits.deleteLink(meta, types, "knows:Lamp.control"), meta))
        assertTrue(unlinked.links.none { it.id == "knows:Lamp.control" })
    }

    @Test
    fun `the type of a property and the target type of a relation are set to any text, and a list stays a list`() {
        val property = StructureMember.Row("Bulb", 0)
        assertEquals("number", StructureEdits.valueOf(meta, types, property))
        val typed = applied(TypeEdits.setType(meta, types, property, "string"), meta)
        assertEquals(listOf("maxWatts: string", "lit: boolean"), readTypes(typed).node("Bulb")?.rows)

        val list = StructureMember.Row("Switch", 0)
        assertEquals("string", StructureEdits.valueOf(meta, types, list))
        assertTrue("prop(\"presses\", list(\"number\"))" in applied(TypeEdits.setType(meta, types, list, "number"), meta))

        val unknown = readTypes(applied(TypeEdits.setType(meta, types, StructureMember.Link("has:Lamp.spare"), "Spare \"part\""), meta))
        assertTrue(unknown.links.none { it.id == "has:Lamp.spare" }, "a relation to a type the file does not declare is a row")
        assertEquals(listOf("mode: string", "spare: Spare 'part'", "shade: Shade"), unknown.node("Lamp")?.rows)

        val declared = readTypes(applied(TypeEdits.setType(meta, types, StructureMember.Row("Lamp", 1), "Bulb"), meta))
        assertTrue(declared.links.any { it.id == "has:Lamp.shade" && it.target == "Bulb" })
        assertNull(TypeEdits.setType(meta, types, property, "number"), "the type is the same")
    }

    @Test
    fun `a rule becomes a list and a single value again, as dsl-tools reads it`() {
        val watts = StructureMember.Row("Bulb", 0)
        assertFalse(TypeEdits.isList(clean, cleanTypes, watts))
        val listed = applied(TypeEdits.setList(clean, cleanTypes, watts, list = true), clean)
        assertTrue("prop(\"watts\", list(\"number\"))" in listed, listed)
        assertTrue(metamodelOf(listed).getTypeByName("Bulb")!!.getSimpleProperty("watts").isList)
        assertTrue(TypeEdits.isList(listed, readTypes(listed), watts))
        assertNull(TypeEdits.setList(listed, readTypes(listed), watts, list = true), "it is a list already")
        assertEquals(clean, applied(TypeEdits.setList(listed, readTypes(listed), watts, list = false), listed))

        val bulb = StructureMember.Link("has:Lamp.bulb")
        val lamps = applied(TypeEdits.setList(clean, cleanTypes, bulb, list = true), clean)
        assertEquals("bulb [*]", readTypes(lamps).links.single { it.id == "has:Lamp.bulb" }.label)
        assertTrue(metamodelOf(lamps).getTypeByName("Lamp")!!.getObjectProperty("bulb").isList)
    }

    @Test
    fun `a has relation becomes a knows relation and back, as dsl-tools reads it`() {
        val bulb = StructureMember.Link("has:Lamp.bulb")
        assertEquals(LinkKind.CONTAINMENT, TypeEdits.kindOf(clean, cleanTypes, bulb))
        val known = applied(TypeEdits.setKind(clean, cleanTypes, bulb, LinkKind.REFERENCE), clean)
        assertTrue(readTypes(known).links.any { it.id == "knows:Lamp.bulb" && it.kind == LinkKind.REFERENCE })
        assertEquals(AssociationType.LINK, metamodelOf(known).getTypeByName("Lamp")!!.getObjectProperty("bulb").associationType)
        assertEquals(clean, applied(TypeEdits.setKind(known, readTypes(known), StructureMember.Link("knows:Lamp.bulb"), LinkKind.CONTAINMENT), known))

        assertNull(TypeEdits.setKind(clean, cleanTypes, bulb, LinkKind.CONTAINMENT), "it is a has relation already")
        assertNull(TypeEdits.setKind(clean, cleanTypes, StructureMember.Row("Bulb", 0), LinkKind.REFERENCE), "a property is no relation")
        val shade = StructureMember.Row("Lamp", 1)
        assertEquals(RowKind.CONTAINMENT, types.node("Lamp")?.rowKinds?.get(1))
        assertTrue("knows(\"shade\", \"Shade\")" in applied(TypeEdits.setKind(meta, types, shade, LinkKind.REFERENCE), meta))
    }

    @Test
    fun `a type is described and made the root type`() {
        assertEquals("A light bulb", TypeEdits.descriptionOf(meta, types, "Bulb"))
        val described = applied(TypeEdits.describe(meta, types, "Bulb", "A bulb with a filament"), meta)
        assertEquals("A bulb with a filament", TypeEdits.descriptionOf(described, readTypes(described), "Bulb"))
        assertNull(TypeEdits.describe(meta, types, "Bulb", "A light bulb"))

        val rooted = readTypes(applied(TypeEdits.makeRoot(meta, types, "Lamp"), meta))
        assertEquals(listOf("Bulb", "Switch", "Room", "Lamp"), rooted.nodes.map { it.id })
        assertEquals(types.node("Lamp")?.rows, rooted.node("Lamp")?.rows)
        assertTrue(TypeEdits.isRoot(types, "Room"))
        assertNull(TypeEdits.makeRoot(meta, types, "Room"), "it is the root type already")
    }

    @Test
    fun `deleting a type deletes the relations that lead to it`() {
        val after = readTypes(applied(TypeEdits.delete(meta, types, "Bulb"), meta))

        assertEquals(listOf("Switch", "Lamp", "Room"), after.nodes.map { it.id })
        assertTrue(after.links.none { it.target == "Bulb" })
        assertEquals(listOf("mode: string", "shade: Shade"), after.node("Lamp")?.rows)
    }

    @Test
    fun `a type gets, changes and loses its behaves after clause in its header`() {
        val added = applied(TypeEdits.behaveAfter(meta, types, "Bulb", "glowing"), meta)
        assertTrue("type \"Bulb\" \"A light bulb\" behaves after \"glowing\" {" in added, added)
        val behaving = readTypes(added)
        val bulb = assertNotNull(behaving.node("Bulb"))
        assertEquals(listOf("behaves after glowing", "maxWatts: number", "lit: boolean"), bulb.rows)
        assertEquals(RowKind.BEHAVIOUR, bulb.rowKinds.first())
        assertEquals("behaves after \"glowing\"", added.substring(bulb.rowRanges[0].start, bulb.rowRanges[0].end))
        val read = metamodelOf(applied(TypeEdits.behaveAfter(clean, cleanTypes, "Bulb", "glowing"), clean))
        assertEquals("glowing", read.getTypeByName("Bulb")?.behavesAfter?.stateMachineName)

        val changed = applied(TypeEdits.behaveAfter(added, behaving, "Bulb", "flickering"), added)
        assertEquals("flickering", readTypes(changed).node("Bulb")?.behaviour?.machine)
        assertNull(TypeEdits.behaveAfter(added, behaving, "Bulb", "glowing"), "the same name changes nothing")

        assertEquals(meta, applied(TypeEdits.behaveAfter(added, behaving, "Bulb", " "), added))
        assertNull(StructureEdits.deleteRow(added, behaving, "Bulb", 0), "the clause is no statement of the body")
        assertNull(TypeEdits.behaveAfter(meta, types, "Bulb", ""), "nothing to remove")
    }

    private companion object {
        /** A metamodel that dsl-tools reads without errors. */
        val CLEAN = """
            type "Bulb" "A light bulb" {
                prop("watts", "number")
                prop("lit", "boolean")
            }
            type "Switch" "A wall switch" {
                prop("presses", list("string"))
            }
            type "Lamp" "A lamp" {
                prop("mode", "string")
                has("bulb", "Bulb")
                knows("control", "Switch")
            }
            type "Room" "A room" {
                has("lamp", "Lamp")
                has("switch", "Switch")
            }
        """.trimIndent()
    }
}
