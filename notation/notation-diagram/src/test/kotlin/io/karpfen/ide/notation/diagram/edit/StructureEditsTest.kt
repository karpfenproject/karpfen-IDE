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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.notation.diagram.LampStructure
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.MetamodelProjection
import io.karpfen.ide.notation.diagram.structure.ModelProjection
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the changes a class diagram and an object diagram make to their texts. Every change is applied to the text,
 * and the new text is read again with the parser of the editor. It must read without syntax errors and give the
 * diagram the change was meant to give.
 */
class StructureEditsTest {

    private val meta = LampStructure.METAMODEL
    private val types = LampStructure.typesOf(meta)
    private val model = LampStructure.MODEL
    private val objects = LampStructure.objectsOf(model)

    @Test
    fun `a new type becomes the first type, so the root type stays the last`() {
        val change = assertNotNull(StructureEdits.addType(meta, types, "Shade", at = DiagramPoint(42f, 58f)))

        val after = readTypes(applied(change, meta))
        assertEquals(listOf("Shade", "Bulb", "Switch", "Lamp", "Room"), after.nodes.map { it.id })
        assertEquals(mapOf("Shade" to DiagramPoint(40f, 60f)), assertNotNull(change.layout)(VisLayout()).boxes)
        assertTrue(after.links.any { it.id == "has:Lamp.shade" }, "the relation to the type that was missing is a link now")
        assertNull(StructureEdits.addType(meta, types, "Lamp"), "the name is taken")
        assertEquals(listOf("Type"), readTypes(applied(assertNotNull(StructureEdits.addType("", readTypes(""), "Type")), "")).nodes.map { it.id })
    }

    @Test
    fun `renaming a type renames it in the relations that lead to it and keeps its place and its lines`() {
        val change = assertNotNull(StructureEdits.rename(meta, types, "Bulb", "Light"))

        val after = readTypes(applied(change, meta))
        assertEquals(listOf("Light", "Switch", "Lamp", "Room"), after.nodes.map { it.id })
        assertEquals(listOf("Light", "Light"), after.links.filter { it.source == "Lamp" && it.kind == LinkKind.CONTAINMENT }.map { it.target })
        val vis = VisLayout(
            boxes = mapOf("Bulb" to DiagramPoint(10f, 20f)),
            waypoints = mapOf("has:Lamp.bulb" to listOf(DiagramPoint(1f, 2f))),
        )
        assertEquals(vis.copy(boxes = mapOf("Light" to DiagramPoint(10f, 20f))), assertNotNull(change.layout)(vis))

        val source = assertNotNull(StructureEdits.rename(meta, types, "Lamp", "Torch"))
        assertEquals(mapOf("has:Torch.bulb" to listOf(DiagramPoint(1f, 2f))), assertNotNull(source.layout)(vis).waypoints)
        assertNull(StructureEdits.rename(meta, types, "Bulb", "Lamp"), "the name is taken")
    }

    @Test
    fun `a type gets a property and relations, and loses them again`() {
        val withProperty = readTypes(applied(assertNotNull(StructureEdits.addProperty(meta, types, "Bulb", "colour")), meta))
        assertEquals(listOf("maxWatts: number", "lit: boolean", "colour: string"), withProperty.node("Bulb")?.rows)

        val related = readTypes(applied(assertNotNull(StructureEdits.addRelation(meta, types, "Room", LinkKind.REFERENCE, "Bulb")), meta))
        assertTrue(related.links.any { it.id == "knows:Room.bulb" && it.target == "Bulb" })
        val second = readTypes(applied(assertNotNull(StructureEdits.addRelation(meta, types, "Lamp", LinkKind.CONTAINMENT, "Bulb")), meta))
        assertTrue(second.links.any { it.id == "has:Lamp.bulb2" }, "the name bulb is taken in Lamp: ${second.links.map { it.id }}")

        val unlinked = readTypes(applied(assertNotNull(StructureEdits.deleteLink(meta, types, "knows:Lamp.control")), meta))
        assertTrue(unlinked.links.none { it.id == "knows:Lamp.control" })
        val renamed = readTypes(applied(assertNotNull(StructureEdits.renameLink(meta, types, "has:Lamp.spare", "reserve")), meta))
        assertTrue(renamed.links.any { it.id == "has:Lamp.reserve" })

        val row = assertNotNull(StructureEdits.rowText(meta, types, "Bulb", 1))
        assertEquals("prop(\"lit\", \"boolean\")", row)
        val edited = readTypes(applied(assertNotNull(StructureEdits.replaceRow(meta, types, "Bulb", 1, "prop(\"lit\", \"string\")")), meta))
        assertEquals(listOf("maxWatts: number", "lit: string"), edited.node("Bulb")?.rows)
        val shorter = readTypes(applied(assertNotNull(StructureEdits.deleteRow(meta, types, "Bulb", 0)), meta))
        assertEquals(listOf("lit: boolean"), shorter.node("Bulb")?.rows)
    }

    @Test
    fun `a type is described and made the root type`() {
        assertEquals("A light bulb", StructureEdits.descriptionOf(meta, types, "Bulb"))
        val described = applied(assertNotNull(StructureEdits.describe(meta, types, "Bulb", "A bulb with a filament")), meta)
        assertEquals("A bulb with a filament", StructureEdits.descriptionOf(described, readTypes(described), "Bulb"))
        assertNull(StructureEdits.describe(meta, types, "Bulb", "A light bulb"))

        val rooted = readTypes(applied(assertNotNull(StructureEdits.makeRoot(meta, types, "Lamp")), meta))
        assertEquals(listOf("Bulb", "Switch", "Room", "Lamp"), rooted.nodes.map { it.id })
        assertEquals(types.node("Lamp")?.rows, rooted.node("Lamp")?.rows)
        assertNull(StructureEdits.makeRoot(meta, types, "Room"), "it is the root type already")
    }

    @Test
    fun `deleting a type deletes the relations that lead to it`() {
        val after = readTypes(applied(assertNotNull(StructureEdits.delete(meta, types, "Bulb")), meta))

        assertEquals(listOf("Switch", "Lamp", "Room"), after.nodes.map { it.id })
        assertTrue(after.links.none { it.target == "Bulb" })
        assertEquals(listOf("mode: string", "shade: Shade"), after.node("Lamp")?.rows)
    }

    @Test
    fun `an object is renamed with the references to it, and gets a property, an object and a reference`() {
        val renamed = readObjects(applied(assertNotNull(StructureEdits.rename(model, objects, "wallSwitch", "toggle")), model))
        assertTrue(renamed.links.any { it.id == "knows:lamp.control" && it.target == "toggle" })

        val withProperty = readObjects(applied(assertNotNull(StructureEdits.addProperty(model, objects, "bulb", "lit")), model))
        assertEquals(listOf("maxWatts = 40", "lit = "), withProperty.node("bulb")?.rows)

        val withObject = readObjects(applied(assertNotNull(StructureEdits.addObject(model, objects, "lamp", "shade", "Shade")), model))
        assertTrue(withObject.links.any { it.id == "has:lamp.shade" && it.target == "shade" })
        assertEquals("shade : Shade", withObject.node("shade")?.title)
        assertNull(StructureEdits.addObject(model, objects, "lamp", "shade", " "), "an object needs a type")

        val withReference = readObjects(applied(assertNotNull(StructureEdits.addReference(model, objects, "wallSwitch", "bulb")), model))
        assertTrue(withReference.links.any { it.id == "knows:wallSwitch.ref" && it.target == "bulb" })
    }

    @Test
    fun `deleting an object deletes what it contains and the references to it, but never the outermost object`() {
        val after = readObjects(applied(assertNotNull(StructureEdits.delete(model, objects, "wallSwitch")), model))
        assertEquals(listOf("living room", "lamp", "bulb", ":Bulb"), after.nodes.map { it.id })
        assertEquals(listOf("mode = off", "fuse = fuseBox"), after.node("lamp")?.rows, "the reference to the switch is gone")

        val withoutLamp = readObjects(applied(assertNotNull(StructureEdits.delete(model, objects, "lamp")), model))
        assertEquals(listOf("living room", "wallSwitch"), withoutLamp.nodes.map { it.id })
        assertEquals(withoutLamp.nodes, readObjects(applied(assertNotNull(StructureEdits.deleteLink(model, objects, "has:living room.lamps")), model)).nodes)
        assertNull(StructureEdits.delete(model, objects, "living room"))
    }

    private fun applied(change: DiagramChange, source: String): String =
        change.edits.sortedByDescending { it.range.start }.fold(source) { now, edit -> now.replaceRange(edit.range.start, edit.range.end, edit.newText) }

    private fun readTypes(source: String): StructureDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KMETA).parser.parse(TextSnapshot(source, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors:\n$source")
        return MetamodelProjection.of(outcome.outline)
    }

    private fun readObjects(source: String): StructureDiagram {
        val outcome = defaultLanguageRegistry().support(KarpfenLanguage.KMODEL).parser.parse(TextSnapshot(source, 0))
        assertTrue(outcome.isSyntaxClean, "the text has syntax errors:\n$source")
        return ModelProjection.of(outcome.outline)
    }
}
