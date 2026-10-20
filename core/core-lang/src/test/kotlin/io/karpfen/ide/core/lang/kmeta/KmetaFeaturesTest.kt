package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.fixtures.at
import io.karpfen.ide.core.lang.fixtures.textAt
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** KMeta completion, navigation and hover at concrete carets of the examples (spec §5). */
class KmetaFeaturesTest {

    private val probe = ServicesProbe()
    private val metamodel = ExampleProject.example(ExamplePaths.ROBOT_KMETA)
    private val text = metamodel.text.text
    private val domainTypes = listOf("Obstacle", "Robot", "Room", "TwoDObject", "Vector", "Wall")

    @Test
    fun `top level offers the type snippet with the caret in its name`() {
        val items = probe.complete(metamodel, text.length)

        val item = items.single()
        assertEquals("type \"Name\" \"Description\" { }", item.label)
        assertEquals(CompletionKind.SNIPPET, item.kind)
        assertEquals("type \"\" \"\" { }", item.insertText)
        assertEquals(6, item.caretOffsetInInsert)
        assertEquals(TextRange.empty(text.length), item.replaceRange)
    }

    @Test
    fun `a type body offers the rule snippets`() {
        val offset = after(text, "prop(\"y\", \"number\")\n")

        assertEquals(
            listOf("has(\"…\", \"…\")", "knows(\"…\", \"…\")", "list(\"…\")", "prop(\"…\", \"…\")"),
            probe.labels(metamodel, offset),
        )
        assertEquals("prop(\"\", \"\")", probe.complete(metamodel, offset).single { it.label.startsWith("prop") }.insertText)
    }

    @Test
    fun `the second argument of prop offers the primitives, also inside list`() {
        val offset = after(text, "prop(\"x\", \"num")
        val items = probe.complete(metamodel, offset)

        assertEquals(listOf("boolean", "number", "string"), items.map { it.label }.sorted())
        assertTrue(items.all { it.kind == CompletionKind.PRIMITIVE_TYPE && it.insertText == it.label })
        assertEquals("number", text.substring(items.first().replaceRange.start, items.first().replaceRange.end))
        assertEquals(listOf("boolean", "number", "string"), probe.labels(metamodel, after(text, "prop(\"log\", list(\"")))
    }

    @Test
    fun `the type argument of has, knows and list offers the types of the file`() {
        assertEquals(domainTypes, probe.labels(metamodel, after(text, "has(\"position\", \"")))
        assertEquals(domainTypes, probe.labels(metamodel, after(text, "knows(\"obstacles\", list(\"")))
        val item = probe.complete(metamodel, after(text, "has(\"position\", \"Vec")).single { it.label == "Vector" }
        assertEquals(CompletionKind.TYPE, item.kind)
        assertEquals("A vector in 2D space", item.detail)
        assertTrue(assertNotNull(item.documentation).startsWith("type Vector"))
    }

    @Test
    fun `outside the quotes the names are inserted quoted and list is offered`() {
        val (document, offset) = edited {
            it.replace("has(\"position\", \"Vector\")", "has(\"position\", )") to after(it, "has(\"position\", ")
        }

        val items = probe.complete(document, offset)

        assertEquals("\"Vector\"", items.single { it.label == "Vector" }.insertText)
        assertTrue(items.any { it.label == "list(\"…\")" })
    }

    @Test
    fun `the events file offers its own types first, then the context's domain types`() {
        val events = ExampleProject.example(ExamplePaths.ROBOT_EVENTS)
        val offset = after(events.text.text, "has(\"position\", \"")

        val labels = probe.labels(events, offset)

        assertEquals(listOf("setSpeed", "start", "teleport") + domainTypes, labels)
    }

    @Test
    fun `unterminated strings still complete`() {
        val (document, offset) = edited {
            it.replace("has(\"position\", \"Vector\")\n}", "has(\"position\", \"Ve\n}") to after(it, "has(\"position\", \"Ve")
        }

        val item = probe.complete(document, offset).single { it.label == "Vector" }
        assertEquals("Ve", document.text.substring(item.replaceRange))
    }

    @Test
    fun `a type reference navigates to its declaration in the file, from the events file to the domain metamodel`() {
        val local = probe.definition(metamodel, after(text, "has(\"position\", \"Vec")).single()
        assertEquals(metamodel.path, local.path)
        assertEquals("Vector", textAt(text, local))
        assertEquals(metamodel.text.version, local.version)
        assertSame(metamodel.text.lineage, local.lineage)

        val events = ExampleProject.example(ExamplePaths.ROBOT_EVENTS)
        val crossFile = probe.definition(events, after(events.text.text, "has(\"position\", \"Vec")).single()
        val analyzed = assertNotNull(ExampleProject.analysis.file(ExampleProject.pathOf(ExamplePaths.ROBOT_KMETA)))
        assertEquals(analyzed.path, crossFile.path)
        assertEquals("Vector", analyzed.snapshot.substring(crossFile.range))
        assertSame(analyzed.snapshot.lineage, crossFile.lineage)
    }

    @Test
    fun `references of a type span the modeling context`() {
        val references = probe.references(metamodel, at(text, "Vector\" \"A vector") + 1)

        val byFile = references.groupBy { it.path.fileName.toString() }
        assertEquals(setOf("cleaning_robot.kmeta", "EVENTS.kmeta", "cleaning_robot.kmodel", "cleaning_robot.kstates"), byFile.keys)
        assertEquals(1 + 4, byFile.getValue("cleaning_robot.kmeta").size, "declaration and four has() targets")
        assertEquals(1, byFile.getValue("EVENTS.kmeta").size)
        assertEquals(textOfAll(ExamplePaths.ROBOT_KMODEL, byFile.getValue("cleaning_robot.kmodel")).toSet(), setOf("Vector"))
        assertTrue(byFile.getValue("cleaning_robot.kstates").size >= 9, "TAKES types")
    }

    @Test
    fun `references of a member include KModel keys and KStates path segments of that type only`() {
        val references = probe.references(metamodel, after(text, "prop(\"") + 0)

        val kmodel = references.filter { it.path == ExampleProject.pathOf(ExamplePaths.ROBOT_KMODEL) }
        assertEquals(12, kmodel.size, "prop(\"x\") in the twelve Vector objects")
        val kstates = references.filter { it.path == ExampleProject.pathOf(ExamplePaths.ROBOT_KSTATES) }
        val kstatesText = ExampleProject.textOf(ExamplePaths.ROBOT_KSTATES)
        assertTrue(kstates.all { textAt(kstatesText, it) == "x" })
        assertTrue(kstates.any { it.range.start > at(kstatesText, "SET(\"boundingBox->position->x\"") }, "an action path")
        val parameterPath = at(kstatesText, "\$(p1->x)")
        assertTrue(kstates.any { it.range.start in parameterPath..parameterPath + 8 }, "a macro parameter path")
        val bindingPath = at(kstatesText, "\$(dir->x)")
        assertTrue(kstates.any { it.range.start in bindingPath..bindingPath + 8 }, "a WITH binding path")
        assertTrue(references.any { it.path == metamodel.path && textAt(text, it) == "x" }, "the declaration")
    }

    @Test
    fun `hover shows the type with members and member signatures`() {
        val type = assertNotNull(probe.hover(metamodel, after(text, "has(\"boundingBox\", \"Two")))
        assertEquals(
            "type TwoDObject\nA two-dimensional object with a position and diameter\n\nprop diameter: number\nhas position: Vector",
            type.text,
        )
        assertEquals("TwoDObject", text.substring(type.range.start, type.range.end))

        val member = assertNotNull(probe.hover(metamodel, after(text, "knows(\"obst")))
        assertEquals("knows obstacles: list(Obstacle)", member.text)
        assertNull(probe.hover(metamodel, after(text, "prop(\"x\", \"num")), "primitives have no hover")
    }

    private fun edited(edit: (String) -> Pair<String, Int>): Pair<LanguageDocument, Int> {
        val (changed, offset) = edit(text)
        return ExampleProject.document(metamodel.path, changed, version = 2) to offset
    }

    private fun textOfAll(examplePath: String, locations: List<Location>): List<String> =
        ExampleProject.textOf(examplePath).let { source -> locations.map { textAt(source, it) } }
}
