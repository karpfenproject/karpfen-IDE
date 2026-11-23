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

import io.karpfen.ide.core.lang.kmodel.KmodelObjectWriter
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin
import io.karpfen.ide.notation.diagram.edit.StructureTexts.applied
import io.karpfen.ide.notation.diagram.edit.StructureTexts.readObjects
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.StructureArrangement
import io.karpfen.ide.notation.diagram.layout.StructureLayout
import io.karpfen.ide.notation.diagram.layout.StructureMetrics
import io.karpfen.ide.notation.diagram.layout.TextMeasurer
import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.structure.StructureDiagram
import io.karpfen.ide.notation.diagram.structure.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The entry "New object of" of the object diagram: its second level of the types of the metamodel on an object and on
 * the empty canvas, with types and without, the relation its field starts with, and the whole object it writes, which
 * is the text the completion of the text editor inserts for the type. Every written text is read again by the parser of
 * the editor, which must find no syntax error.
 */
class NewObjectOfTest {

    private val writer = KmodelObjectWriter(TYPES)
    private val actions = ObjectActions(writer)
    private val objects = readObjects(MODEL)
    private val layout = StructureArrangement.automatic(objects, TextMeasurer.estimating())

    @Test
    fun `an object offers the types of the metamodel in their order in the second level of New object of`() {
        val entries = actions.at(MODEL, objects, layout, onTitle("node"))
        val labels = entries.map { it.label }

        assertEquals(labels.indexOf("Add object") + 1, labels.indexOf("New object of"), "$labels")
        val submenu = assertIs<DiagramAction.Submenu>(entries.single { it.label == "New object of" })
        assertEquals(listOf("Part", "Node", "Holder", "Net"), submenu.entries.map { it.label })
        assertTrue(submenu.entries.all { it is DiagramAction.Ask }, "each type asks for the relation")
    }

    @Test
    fun `without types the entry is there all the same and its second level says so`() {
        val submenu = assertIs<DiagramAction.Submenu>(ObjectActions().at(MODEL, objects, layout, onTitle("node")).single { it.label == "New object of" })

        assertEquals(emptyList(), submenu.entries)
        assertEquals("No types", submenu.empty)
    }

    @Test
    fun `the empty canvas offers the types as well, and without types it says so`() {
        val nothing = StructureDiagram(StructureKind.OBJECTS, emptyList(), emptyList())
        val canvas = DiagramPoint(0f, 0f)

        val entries = actions.at("", nothing, StructureLayout.EMPTY, canvas)
        assertEquals(listOf("Add root object", "New object of"), entries.map { it.label })
        assertEquals(listOf("Part", "Node", "Holder", "Net"), assertIs<DiagramAction.Submenu>(entries.last()).entries.map { it.label })

        val untyped = assertIs<DiagramAction.Submenu>(ObjectActions().at("", nothing, StructureLayout.EMPTY, canvas).last())
        assertEquals(emptyList(), untyped.entries)
        assertEquals("No types", untyped.empty)

        assertEquals(emptyList(), actions.at(MODEL, objects, layout, DiagramPoint(-500f, -500f)), "a model with its root has no canvas entries")
    }

    @Test
    fun `the field starts with the relation that leads to the chosen type, one the object lacks or a list first`() {
        assertEquals("second", relationOffered("hub", "Node"), "first is taken, second is free")
        assertEquals("parts", relationOffered("node", "Part"), "part is taken, parts is a list")
        assertEquals("child", relationOffered("node", "Node"))
        assertEquals("hub", relationOffered("net", "Holder"), "the only such relation, although the object has it")
        assertEquals("relation", relationOffered("node", "Net"), "the type has no relation to Net")
        assertEquals("relation", relationOffered("ghost", "Part"), "the type of the object is unknown")
    }

    @Test
    fun `the chosen type is written as a whole object, indented for an object nested two levels deep`() {
        val written = applied(choose("node", "Node", "child"), MODEL)

        val expected = """
            |            has("child") -> make object "child":"Node" {
            |                prop("name") -> ""
            |                prop("weight") -> "0"
            |                prop("active") -> "false"
            |                prop("tags") -> ""
            |                has("part") -> make object "part":"Part" {
            |                    prop("label") -> ""
            |                }
            |                has("parts") -> make object "parts":"Part" {
            |                    prop("label") -> ""
            |                }
            |                knows("peer") -> ""
            |                has("child") -> make object "child2":"Node" { }
            |            }
            |        }
        """.trimMargin()
        assertTrue(expected in written, written)
        val diagram = readObjects(written)
        assertTrue(diagram.links.any { it.id == "has:node.child" && it.target == "child" }, "${diagram.links}")
        assertTrue(diagram.links.any { it.id == "has:child.child" && it.target == "child2" }, "${diagram.links}")
    }

    @Test
    fun `the text is the one the completion writes for the type`() {
        val written = applied(choose("net", "Part", "extra"), MODEL)
        val template = writer.contained("extra", "Part", setOf("net", "hub", "node", "p", "ghost")).text

        assertTrue("    has(\"extra\") -> ${template.replace("\n", "\n    ")}\n}" in written, written)
        readObjects(written)
    }

    @Test
    fun `a key the file uses gets a number, and so does the key of an object inside it`() {
        val written = applied(choose("hub", "Node", "node"), MODEL)

        assertTrue("has(\"node\") -> make object \"node2\":\"Node\" {" in written, written)
        assertTrue("has(\"part\") -> make object \"part\":\"Part\" {" in written, "part is no key of the file yet; $written")
        val again = applied(choose("hub", "Holder", "p"), MODEL)
        assertTrue("has(\"p\") -> make object \"p2\":\"Holder\" {" in again, again)
        readObjects(written)
        readObjects(again)
    }

    @Test
    fun `an empty relation gives an object without an id, and the relation and the key are written alike`() {
        val empty = applied(choose("net", "Part", "  "), MODEL)
        assertTrue("has(\"\") -> make object \"\":\"Part\" {" in empty, empty)
        readObjects(empty)

        val slashed = applied(choose("net", "Part", "a\\b"), MODEL)
        assertTrue("has(\"a\\\\b\") -> make object \"a\\\\b\":\"Part\" {" in slashed, slashed)
        readObjects(slashed)
    }

    @Test
    fun `the empty canvas writes the root object of the chosen type, keyed by its name, where the user clicked`() {
        val nothing = StructureDiagram(StructureKind.OBJECTS, emptyList(), emptyList())
        val submenu = assertIs<DiagramAction.Submenu>(actions.at("", nothing, StructureLayout.EMPTY, DiagramPoint(42f, 58f)).last())
        val change = assertNotNull(assertIs<DiagramAction.Change>(submenu.entries.single { it.label == "Holder" }).change())

        val written = applied(change, "")
        assertEquals(
            """
            make object "holder":"Holder" {
                has("first") -> make object "first":"Node" {
                    prop("name") -> ""
                    prop("weight") -> "0"
                    prop("active") -> "false"
                    prop("tags") -> ""
                    has("part") -> make object "part":"Part" {
                        prop("label") -> ""
                    }
                    has("parts") -> make object "parts":"Part" {
                        prop("label") -> ""
                    }
                    knows("peer") -> ""
                    has("child") -> make object "child":"Node" { }
                }
                has("second") -> make object "second":"Node" {
                    prop("name") -> ""
                    prop("weight") -> "0"
                    prop("active") -> "false"
                    prop("tags") -> ""
                    has("part") -> make object "part2":"Part" {
                        prop("label") -> ""
                    }
                    has("parts") -> make object "parts2":"Part" {
                        prop("label") -> ""
                    }
                    knows("peer") -> ""
                    has("child") -> make object "child2":"Node" { }
                }
            }

            """.trimIndent(),
            written,
        )
        assertTrue(change.arranges, "the root is placed where the user clicked")
        assertEquals(mapOf("holder" to DiagramPoint(40f, 60f)), assertNotNull(change.layout)(VisLayout()).boxes)
        assertEquals("holder : Holder", readObjects(written).node("holder")?.title)
        assertNull(ObjectEdits.addRootOf(MODEL, objects, "Holder", writer), "the model has its root object")
    }

    /** The text the field of the type [type] in "New object of" on the object [id] starts with. */
    private fun relationOffered(id: String, type: String): String = ask(id, type).initial

    /** The change that "New object of", the type [type] and the relation [relation] make on the object [id]. */
    private fun choose(id: String, type: String, relation: String): DiagramChange =
        assertNotNull(assertIs<DiagramAction.Change>(ask(id, type).next(relation)).change())

    /** The field of the type [type] in the second level of "New object of" on the object [id]. */
    private fun ask(id: String, type: String): DiagramAction.Ask {
        val submenu = assertIs<DiagramAction.Submenu>(actions.at(MODEL, objects, layout, onTitle(id)).single { it.label == "New object of" })
        return assertIs<DiagramAction.Ask>(submenu.entries.single { it.label == type })
    }

    /** A point in the title of the box [id] that lies on no line and no label. */
    private fun onTitle(id: String): DiagramPoint {
        val box = layout.boxes.getValue(id)
        val y = box.y + StructureMetrics.TITLE_HEIGHT / 2f
        val point = generateSequence(box.x + 2f) { it + 2f }.takeWhile { it < box.right - 2f }.map { DiagramPoint(it, y) }
            .first { layout.labelAt(it) == null && layout.lineAt(it, DiagramLayout.HIT_TOLERANCE) == null }
        assertEquals(id, layout.boxAt(point))
        return point
    }

    private companion object {
        /** A property of the type [type]. */
        fun prop(name: String, type: String, list: Boolean = false) = MemberInfo(name, MemberKind.PROPERTY, type, list, null)

        /** A `has` relation to [target]. */
        fun has(name: String, target: String, list: Boolean = false) = MemberInfo(name, MemberKind.HAS, target, list, null)

        /** A `knows` relation to [target]. */
        fun knows(name: String, target: String) = MemberInfo(name, MemberKind.KNOWS, target, false, null)

        /** A domain type with [members]. */
        fun type(name: String, vararg members: MemberInfo) = TypeInfo(name, null, members.toList(), TypeOrigin.DOMAIN, null)

        /**
         * The types of the metamodel: properties of the three kinds and a list property, a `has` and a `has` list, a
         * `knows`, a type that contains itself and a type with two relations to the same type.
         */
        val TYPES = listOf(
            type("Part", prop("label", "string")),
            type(
                "Node",
                prop("name", "string"),
                prop("weight", "number"),
                prop("active", "boolean"),
                prop("tags", "string", list = true),
                has("part", "Part"),
                has("parts", "Part", list = true),
                knows("peer", "Node"),
                has("child", "Node"),
            ),
            type("Holder", has("first", "Node"), has("second", "Node")),
            type("Net", has("hub", "Holder")),
        )

        /** A net whose hub holds a node two levels down, and an object of a type the metamodel does not declare. */
        val MODEL = """
            make object "net":"Net" {
                has("hub") -> make object "hub":"Holder" {
                    has("first") -> make object "node":"Node" {
                        prop("name") -> "a"
                        has("part") -> make object "p":"Part" { }
                    }
                }
                has("extra") -> make object "ghost":"Ghost" { }
            }
        """.trimIndent()
    }
}
