package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.fixtures.at
import io.karpfen.ide.core.lang.fixtures.textAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** KModel completion, navigation and hover at concrete carets of the examples (spec §5). */
class KmodelFeaturesTest {

    private val probe = ServicesProbe()
    private val model = ExampleProject.example(ExamplePaths.ROBOT_KMODEL)
    private val text = model.text.text
    private val metamodelPath = ExampleProject.pathOf(ExamplePaths.ROBOT_KMETA)
    private val metamodelText = ExampleProject.textOf(ExamplePaths.ROBOT_KMETA)

    @Test
    fun `an object body offers the statement snippets`() {
        val offset = after(text, "prop(\"d_closest_wall\") -> \"100.0\" // Will be updated by the state machine\n")

        assertEquals(
            listOf("has(\"…\") -> make object \"…\":\"…\" { }", "knows(\"…\") -> \"…\"", "prop(\"…\") -> \"…\""),
            probe.labels(model, offset),
        )
        val has = probe.complete(model, offset).single { it.label.startsWith("has") }
        assertEquals("has(\"\") -> make object \"\":\"\" { }", has.insertText)
        assertEquals(5, has.caretOffsetInInsert)
    }

    @Test
    fun `prop keys are the simple properties of the enclosing object's type`() {
        val items = probe.complete(model, after(text, "prop(\"d_closest_o"))

        assertEquals(listOf("d_closest_obstacle", "d_closest_wall", "log"), items.map { it.label }.sorted())
        assertTrue(items.all { it.kind == CompletionKind.PROPERTY })
        assertEquals("d_closest_obstacle", text.substring(items.first().replaceRange.start, items.first().replaceRange.end))
        assertEquals("list(string)", items.single { it.label == "log" }.detail)
        assertEquals("prop log: list(string)", items.single { it.label == "log" }.documentation)
    }

    @Test
    fun `has keys are only has relations and knows keys only knows relations`() {
        assertEquals(listOf("boundingBox", "direction"), probe.labels(model, after(text, "has(\"bou")))
        assertEquals(
            listOf("closest_obstacle", "closest_wall", "obstacles", "walls"),
            probe.labels(model, after(text, "knows(\"")),
        )
        val boxBody = after(text, "make object \"turtleBoundingBox\":\"TwoDObject\" {\n            prop(\"")
        assertEquals(listOf("diameter"), probe.labels(model, boxBody))
    }

    @Test
    fun `the type of a has value offers the relation's target type first`() {
        val labels = probe.labels(model, after(text, "make object \"turtle\":\""))

        assertEquals("Robot", labels.first())
        assertEquals(setOf("Obstacle", "Robot", "Room", "TwoDObject", "Vector", "Wall"), labels.toSet())
        val root = probe.complete(model, after(text, "make object \"APB 2101\":\""))
        assertTrue(root.all { it.sortKey.startsWith("1:") }, "the root has no relation")
    }

    @Test
    fun `knows targets are the objects of the relation's type`() {
        assertEquals(listOf("chair", "table"), probe.labels(model, after(text, "knows(\"obstacles\") -> \"")))
        assertEquals(
            listOf("wall_bottom", "wall_left", "wall_right", "wall_top"),
            probe.labels(model, after(text, "knows(\"closest_wall\") -> \"")),
        )
        val item = probe.complete(model, after(text, "knows(\"obstacles\") -> \"")).first()
        assertEquals(CompletionKind.OBJECT, item.kind)
        assertEquals("Obstacle", item.detail)
    }

    @Test
    fun `keys outside the quotes are inserted quoted and an empty model offers the root object`() {
        val open = ExampleProject.document(model.path, text.replace("prop(\"diameter\") -> \"0.2\"", "prop()"), version = 2)
        val offset = after(open.text.text, "prop()") - 1
        assertEquals("\"diameter\"", probe.complete(open, offset).single().insertText)

        val empty = ExampleProject.document(model.path, "", version = 3)
        assertEquals(listOf("make object \"…\":\"…\" { }"), probe.labels(empty, 0))
    }

    @Test
    fun `navigation leads to objects in the file and to types and members in the metamodel`() {
        val chair = probe.definition(model, after(text, "knows(\"obstacles\") -> \"ch")).single()
        assertEquals(model.path, chair.path)
        assertEquals(at(text, "chair\":\"Obstacle\""), chair.range.start)

        val type = probe.definition(model, after(text, "\"turtle\":\"Ro")).single()
        assertEquals(metamodelPath, type.path)
        assertEquals("Robot", textAt(metamodelText, type))

        val member = probe.definition(model, after(text, "prop(\"d_closest_w")).single()
        assertEquals(metamodelPath, member.path)
        assertEquals(at(metamodelText, "d_closest_wall"), member.range.start)
    }

    @Test
    fun `references of an object are its declaration and the knows targets naming it`() {
        val references = probe.references(model, after(text, "make object \"cha"))

        assertEquals(3, references.size)
        assertTrue(references.all { it.path == model.path && textAt(text, it) == "chair" })
    }

    @Test
    fun `references of a key are the member declaration and the keys of objects of that type`() {
        val references = probe.references(model, after(text, "prop(\"diam"))

        assertEquals(1, references.count { it.path == metamodelPath })
        assertEquals(3, references.count { it.path == model.path }, "the three TwoDObject objects")
    }

    @Test
    fun `hover shows types, member signatures and objects`() {
        assertTrue(assertNotNull(probe.hover(model, after(text, "\"turtle\":\"Ro"))).text.startsWith("type Robot\n"))
        assertEquals("has boundingBox: TwoDObject", probe.hover(model, after(text, "has(\"bounding"))?.text)
        assertEquals("object chair: Obstacle", probe.hover(model, after(text, "knows(\"obstacles\") -> \"ch"))?.text)
    }

    @Test
    fun `values and anonymous positions have no features`() {
        val value = after(text, "prop(\"diameter\") -> \"0")
        assertTrue(probe.complete(model, value).isEmpty())
        assertTrue(probe.definition(model, value).isEmpty())
        assertEquals(null, probe.hover(model, value))
    }
}
