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

import io.karpfen.dsl.instance.Model
import io.karpfen.ide.notation.diagram.LampStructure
import io.karpfen.ide.notation.diagram.edit.StructureTexts.applied
import io.karpfen.ide.notation.diagram.edit.StructureTexts.modelOf
import io.karpfen.ide.notation.diagram.edit.StructureTexts.readObjects
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.LinkKind
import io.karpfen.ide.notation.diagram.structure.RowKind
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the changes an object diagram makes to its model. Every change is applied to the text, and the new text is
 * read again: by the parser of the editor, which must find no syntax error and gives the diagram the change was meant
 * to give, and by the reader of dsl-tools where that shows what the change means in the language.
 */
class ObjectEditsTest {

    private val model = LampStructure.MODEL
    private val objects = LampStructure.objectsOf(model)

    private val clean = CLEAN_MODEL
    private val cleanObjects = readObjects(CLEAN_MODEL)

    @Test
    fun `the clean model of these tests reads without errors`() {
        assertEquals(emptyList(), modelOf(clean, CLEAN_META).second)
    }

    @Test
    fun `a model without an object gets its root object, whose type is empty until it is set`() {
        val nothing = StructureDiagram(StructureKind.OBJECTS, emptyList(), emptyList())
        val change = assertNotNull(ObjectEdits.addRoot("// the lamp\n", nothing, at = DiagramPoint(42f, 58f)))

        val added = applied(change, "// the lamp\n")
        assertEquals("// the lamp\nmake object \"object\":\"\" { }\n", added)
        assertEquals("object", readObjects(added).nodes.single().title)
        assertEquals(mapOf("object" to DiagramPoint(40f, 60f)), assertNotNull(change.layout)(VisLayout()).boxes)
        assertEquals("make object \"object\":\"\" { }\n", applied(ObjectEdits.addRoot("", nothing), ""))
        assertNull(ObjectEdits.addRoot(model, objects), "the model has its root object")

        val typed = applied(ObjectEdits.setType(added, readObjects(added), "object", "Room"), added)
        assertEquals("object : Room", readObjects(typed).nodes.single().title)
        assertEquals(emptyList(), modelOf(typed, CLEAN_META).second)
    }

    @Test
    fun `an object is renamed with the references to it, also to a taken key`() {
        val renamed = readObjects(applied(ObjectEdits.rename(model, objects, "wallSwitch", "toggle"), model))
        assertTrue(renamed.links.any { it.id == "knows:lamp.control" && it.target == "toggle" })

        val taken = assertNotNull(ObjectEdits.rename(model, objects, "wallSwitch", "lamp"))
        assertNull(taken.layout, "the box is placed anew")
        assertTrue("make object \"lamp\":\"Switch\"" in applied(taken, model))
        assertNull(ObjectEdits.rename(model, objects, "lamp", "lamp"), "the key is the same")
    }

    @Test
    fun `the type of an object is set to any text`() {
        assertEquals("Bulb", ObjectEdits.typeOf(model, objects, "bulb"))
        val typed = readObjects(applied(ObjectEdits.setType(model, objects, "bulb", "Light \"bulb\""), model))
        assertEquals("bulb : Light 'bulb'", typed.node("bulb")?.title)
        assertEquals(": Lamp", readObjects(applied(ObjectEdits.setType(model, objects, ":Bulb", "Lamp"), model)).node(":Lamp")?.title)
        assertNull(ObjectEdits.setType(model, objects, "bulb", "Bulb"))
    }

    @Test
    fun `an object gets a property, an object and a reference, also to itself`() {
        val withProperty = readObjects(applied(ObjectEdits.addProperty(model, objects, "bulb", "lit"), model))
        assertEquals(listOf("maxWatts = 40", "lit = "), withProperty.node("bulb")?.rows)

        val withObject = readObjects(applied(ObjectEdits.addObject(model, objects, "lamp", "shade", "Shade"), model))
        assertTrue(withObject.links.any { it.id == "has:lamp.shade" && it.target == "shade" })
        assertEquals("shade : Shade", withObject.node("shade")?.title)
        val untyped = applied(ObjectEdits.addObject(model, objects, "lamp", "shade", " "), model)
        assertTrue("has(\"shade\") -> make object \"shade\":\"\" { }" in untyped, "an object without a type is written all the same")

        val withReference = readObjects(applied(ObjectEdits.addReference(model, objects, "wallSwitch", "bulb"), model))
        assertTrue(withReference.links.any { it.id == "knows:wallSwitch.ref" && it.target == "bulb" })
        val itself = readObjects(applied(ObjectEdits.addReference(model, objects, "lamp", "lamp"), model))
        assertTrue(itself.links.any { it.id == "knows:lamp.ref" && it.target == "lamp" })
    }

    @Test
    fun `a property gets a value and another value of the same key after it, as dsl-tools reads a list`() {
        val presses = StructureMember.Row("wallSwitch", 0)
        assertEquals("up", StructureEdits.valueOf(clean, cleanObjects, presses))
        val set = applied(ObjectEdits.setValue(clean, cleanObjects, presses, "down"), clean)
        assertEquals(listOf("presses = down"), readObjects(set).node("wallSwitch")?.rows)

        val more = applied(ObjectEdits.addValue(clean, cleanObjects, "wallSwitch", 0), clean)
        assertTrue("        prop(\"presses\") -> \"up\"\n        prop(\"presses\") -> \"\"\n" in more, more)
        val again = readObjects(more)
        val second = applied(ObjectEdits.setValue(more, again, StructureMember.Row("wallSwitch", 1), "down"), more)
        val (read, errors) = modelOf(second, CLEAN_META)
        assertEquals(emptyList(), errors)
        val switch = Model.collectAllObjects(assertNotNull(read).objects).first { it.id == "wallSwitch" }
        assertEquals(listOf<Any>("up", "down"), switch.getProp("presses"))

        assertNull(ObjectEdits.addValue(model, objects, "lamp", 1), "a reference is no property")
    }

    @Test
    fun `a reference leads to any key, drawn as a line or as a row`() {
        val control = StructureMember.Link("knows:lamp.control")
        assertEquals("wallSwitch", StructureEdits.valueOf(model, objects, control))
        val away = readObjects(applied(ObjectEdits.setValue(model, objects, control, "nowhere"), model))
        assertTrue(away.links.none { it.id == "knows:lamp.control" })
        assertEquals(listOf("mode = off", "control = nowhere", "fuse = fuseBox"), away.node("lamp")?.rows)
        assertEquals(RowKind.REFERENCE, away.node("lamp")?.rowKinds?.get(1))

        val fuse = StructureMember.Row("lamp", 1)
        val found = readObjects(applied(ObjectEdits.setValue(model, objects, fuse, "bulb"), model))
        assertTrue(found.links.any { it.id == "knows:lamp.fuse" && it.target == "bulb" })
        assertNull(ObjectEdits.setValue(model, objects, StructureMember.Link("has:lamp.bulb"), "x"), "a has statement makes an object")
    }

    @Test
    fun `an object moves into another object with everything in it, as dsl-tools reads it`() {
        val moved = applied(ObjectEdits.moveInto(clean, cleanObjects, "bulb", "wallSwitch"), clean)
        val after = readObjects(moved)
        assertTrue(after.links.any { it.id == "has:wallSwitch.bulb" && it.target == "bulb" }, after.links.toString())
        assertEquals(listOf("watts = 40"), after.node("bulb")?.rows)
        assertTrue(
            "    has(\"switch\") -> make object \"wallSwitch\":\"Switch\" {\n" +
                "        prop(\"presses\") -> \"up\"\n" +
                "        has(\"bulb\") -> make object \"bulb\":\"Bulb\" {\n" +
                "            prop(\"watts\") -> \"40\"\n" +
                "        }\n" +
                "    }\n" in moved,
            moved,
        )
        val (_, errors) = modelOf(moved, CLEAN_META)
        assertTrue(errors.isNotEmpty(), "a switch has no relation bulb, which dsl-tools reports")

        val outward = readObjects(applied(ObjectEdits.moveInto(clean, cleanObjects, "bulb", "room"), clean))
        assertTrue(outward.links.any { it.id == "has:room.bulb" })
        val last = applied(ObjectEdits.moveInto(clean, cleanObjects, "lamp", "room"), clean)
        assertEquals(listOf("switch", "lamp"), readObjects(last).links.filter { it.source == "room" }.map { it.label })

        assertNull(ObjectEdits.moveInto(clean, cleanObjects, "room", "lamp"), "the root object does not move")
        assertNull(ObjectEdits.moveInto(clean, cleanObjects, "lamp", "bulb"), "an object does not move into what it contains")
        assertNull(ObjectEdits.moveInto(clean, cleanObjects, "lamp", "lamp"))
        assertTrue(ObjectEdits.isRoot(cleanObjects, "room"))
        assertFalse(ObjectEdits.isRoot(cleanObjects, "lamp"))
    }

    @Test
    fun `deleting an object deletes what it contains and the references to it, but never the root object`() {
        val after = readObjects(applied(ObjectEdits.delete(model, objects, "wallSwitch"), model))
        assertEquals(listOf("living room", "lamp", "bulb", ":Bulb"), after.nodes.map { it.id })
        assertEquals(listOf("mode = off", "fuse = fuseBox"), after.node("lamp")?.rows, "the reference to the switch is gone")

        val withoutLamp = readObjects(applied(ObjectEdits.delete(model, objects, "lamp"), model))
        assertEquals(listOf("living room", "wallSwitch"), withoutLamp.nodes.map { it.id })
        assertEquals(withoutLamp.nodes, readObjects(applied(ObjectEdits.deleteLink(model, objects, "has:living room.lamps"), model)).nodes)
        assertNull(ObjectEdits.delete(model, objects, "living room"))

        val unlinked = readObjects(applied(ObjectEdits.deleteLink(model, objects, "knows:lamp.control"), model))
        assertTrue(unlinked.links.none { it.kind == LinkKind.REFERENCE })
    }

    private companion object {
        /** A metamodel for [CLEAN_MODEL]. */
        val CLEAN_META = """
            type "Bulb" "A light bulb" {
                prop("watts", "number")
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

        /** A model that dsl-tools reads against [CLEAN_META] without errors. */
        val CLEAN_MODEL = """
            make object "room":"Room" {
                has("lamp") -> make object "lamp":"Lamp" {
                    prop("mode") -> "off"
                    has("bulb") -> make object "bulb":"Bulb" {
                        prop("watts") -> "40"
                    }
                    knows("control") -> "wallSwitch"
                }
                has("switch") -> make object "wallSwitch":"Switch" {
                    prop("presses") -> "up"
                }
            }
        """.trimIndent()
    }
}
