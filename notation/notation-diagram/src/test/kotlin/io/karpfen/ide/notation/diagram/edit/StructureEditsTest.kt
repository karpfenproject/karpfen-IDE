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

import io.karpfen.ide.notation.diagram.LampStructure
import io.karpfen.ide.notation.diagram.edit.StructureTexts.applied
import io.karpfen.ide.notation.diagram.edit.StructureTexts.readObjects
import io.karpfen.ide.notation.diagram.edit.StructureTexts.readTypes
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the changes that the class diagram and the object diagram share: the keys of statements, the text of a row and
 * the order of the rows. Every change is applied to the text, and the new text is read again with the parser of the
 * editor. It must read without syntax errors and give the diagram the change was meant to give.
 */
class StructureEditsTest {

    private val meta = LampStructure.METAMODEL
    private val types = LampStructure.typesOf(meta)
    private val model = LampStructure.MODEL
    private val objects = LampStructure.objectsOf(model)

    @Test
    fun `a fresh name gets the first free number`() {
        assertEquals("ref", StructureEdits.fresh("ref", setOf("control")))
        assertEquals("ref3", StructureEdits.fresh("ref", setOf("ref", "ref2")))
    }

    @Test
    fun `the key of a property row and of a relation is renamed, and a link keeps its lines`() {
        val watts = StructureMember.Row("Bulb", 0)
        assertEquals("maxWatts", StructureEdits.keyOf(meta, types, watts))
        val renamed = readTypes(applied(StructureEdits.renameMember(meta, types, watts, "watts"), meta))
        assertEquals(listOf("watts: number", "lit: boolean"), renamed.node("Bulb")?.rows)

        val spare = assertNotNull(StructureEdits.renameMember(meta, types, StructureMember.Link("has:Lamp.spare"), "reserve"))
        assertTrue(readTypes(applied(spare, meta)).links.any { it.id == "has:Lamp.reserve" })
        val vis = VisLayout(waypoints = mapOf("has:Lamp.spare" to listOf(DiagramPoint(1f, 2f))))
        assertEquals(setOf("has:Lamp.reserve"), assertNotNull(spare.layout)(vis).waypoints.keys)

        val value = readObjects(applied(StructureEdits.renameMember(model, objects, StructureMember.Row("lamp", 0), "state"), model))
        assertEquals(listOf("state = off", "fuse = fuseBox"), value.node("lamp")?.rows)
        val made = readObjects(applied(StructureEdits.renameMember(model, objects, StructureMember.Link("has:lamp.bulb"), "light"), model))
        assertTrue(made.links.any { it.id == "has:lamp.light" && it.target == "bulb" })
        assertTrue("prop(\"\", \"boolean\")" in applied(StructureEdits.renameMember(meta, types, StructureMember.Row("Bulb", 1), ""), meta))
        assertNull(StructureEdits.renameMember(meta, types, watts, "maxWatts"), "the key is the same")
    }

    @Test
    fun `a row is edited as text and deleted`() {
        val row = assertNotNull(StructureEdits.rowText(meta, types, "Bulb", 1))
        assertEquals("prop(\"lit\", \"boolean\")", row)
        val edited = readTypes(applied(StructureEdits.replaceRow(meta, types, "Bulb", 1, "prop(\"lit\", \"string\")"), meta))
        assertEquals(listOf("maxWatts: number", "lit: string"), edited.node("Bulb")?.rows)
        val shorter = readTypes(applied(StructureEdits.deleteRow(meta, types, "Bulb", 0), meta))
        assertEquals(listOf("lit: boolean"), shorter.node("Bulb")?.rows)
    }

    @Test
    fun `a row changes places with the row above or below it, and the statements drawn as links stay`() {
        val down = applied(StructureEdits.moveRow(meta, types, "Lamp", 0, up = false), meta)
        assertEquals(listOf("shade: Shade", "mode: string"), readTypes(down).node("Lamp")?.rows)
        assertTrue("    has(\"shade\", \"Shade\")\n    has(\"bulb\", \"Bulb\")\n    has(\"spare\", \"Bulb\")\n    prop(\"mode\", \"string\")\n" in down, down)

        val up = applied(StructureEdits.moveRow(model, objects, "lamp", 1, up = true), model)
        assertEquals(listOf("fuse = fuseBox", "mode = off"), readObjects(up).node("lamp")?.rows)

        assertFalse(StructureEdits.canMoveRow(types, "Lamp", 0, up = true), "the first row has no row above")
        assertFalse(StructureEdits.canMoveRow(types, "Lamp", 1, up = false), "the last row has no row below")
        assertNull(StructureEdits.moveRow(meta, types, "Lamp", 1, up = false))

        val behaving = applied(TypeEdits.behaveAfter(meta, types, "Bulb", "glowing"), meta)
        assertFalse(StructureEdits.canMoveRow(readTypes(behaving), "Bulb", 1, up = true), "the behaves after clause stays in the header")
    }
}
