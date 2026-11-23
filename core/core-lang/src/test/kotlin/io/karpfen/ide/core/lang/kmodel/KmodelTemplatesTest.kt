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
package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.ExampleContext
import io.karpfen.ide.core.document.fixtures.ExampleFile
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.caret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the KModel templates of whole objects over a metamodel with properties of all three types, a list property, a
 * containment, a list of containments, a reference, a list of references, a type that contains itself and a chain of
 * containments deeper than the depth limit. It checks the text, the placeholders in visiting order, the keys, the
 * ranking, the generic template without types, and that every template parses inserted at top level and nested. The
 * object writer of the diagram writes the same text as the templates.
 */
class KmodelTemplatesTest {

    private val analysis = ExampleProject.analyze(listOf(ExampleContext(DIRECTORY, FILES)))
    private val probe = ServicesProbe(analysis)

    /** A document of the model file of the context with [textWithCaret], and the caret. */
    private fun model(textWithCaret: String): Pair<LanguageDocument, Int> {
        val (text, offset) = caret(textWithCaret)
        return ExampleProject.document(ExampleProject.pathOf("$DIRECTORY/graph.kmodel"), text, version = 4) to offset
    }

    /** The templates at the caret of [textWithCaret], in the order of the list for an empty filter. */
    private fun templates(textWithCaret: String): List<CompletionItem> =
        model(textWithCaret).let { (document, offset) -> probe.complete(document, offset) }
            .filter { it.detail == "template" }
            .sortedWith(compareBy({ it.sortKey }, { it.label }))

    /** The texts of the placeholders of [item], in visiting order. */
    private fun placeholders(item: CompletionItem): List<String> = item.placeholders.map { item.insertText.substring(it.start, it.end) }

    /** [textWithCaret] with the insert text of [item] at the caret. */
    private fun inserted(textWithCaret: String, item: CompletionItem): String = textWithCaret.replace("‸", item.insertText)

    /** Fails unless [text] parses as a KModel text without syntax errors. */
    private fun assertParses(text: String) {
        val (document, _) = model("$text‸")
        assertEquals(emptyList(), document.parse!!.syntaxDiagnostics, text)
    }

    @Test
    fun `the context reads without findings`() {
        assertEquals(emptyMap(), analysis.diagnostics)
    }

    @Test
    fun `the top level offers one template per type, each object with every member of its type`() {
        val text = "make object \"node\":\"Node\" {\n}\n‸"
        val node = templates(text).single { it.label.contains("\"Node\"") }

        assertEquals("make object \"node2\":\"Node\" { … }", node.label, "the key node is taken, so the template counts on")
        assertEquals(
            """
            make object "node2":"Node" {
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
                knows("peers") -> ""
                has("child") -> make object "child":"Node" { }
            }
            """.trimIndent(),
            node.insertText,
        )
        assertEquals(
            listOf("node2", "", "0", "false", "", "part", "", "parts", "", "", "", "child"),
            placeholders(node),
            "keys and values in the order they are written, the values of properties and references empty or the default",
        )
        assertEquals(TYPES, templates(text).map { it.label.substringAfter(":\"").substringBefore('"') }.sorted())
        assertParses(inserted("‸", node))
    }

    @Test
    fun `a template goes no deeper than the depth limit, and the keys it writes are unique`() {
        val level0 = templates("‸").single { it.label.contains("\"Level0\"") }

        assertEquals(
            """
            make object "level0":"Level0" {
                has("next") -> make object "next":"Level1" {
                    has("next") -> make object "next2":"Level2" {
                        has("next") -> make object "next3":"Level3" {
                            has("next") -> make object "next4":"Level4" { }
                        }
                    }
                }
            }
            """.trimIndent(),
            level0.insertText,
        )
        assertParses(level0.insertText)
    }

    @Test
    fun `the value of a has statement offers its target type first, keyed by the relation`() {
        val text = "make object \"node\":\"Node\" {\n    has(\"child\") -> ‸\n}\n"
        val (document, offset) = model(text)
        val all = probe.labelsWithoutTemplates(document, offset)
        val offered = templates(text)
        val child = offered.first()

        assertEquals(listOf("make object \"…\":\"…\" { }"), all, "the one-line snippet")
        assertEquals("make object \"child\":\"Node\" { … }", child.label)
        assertTrue(
            child.insertText.endsWith("    has(\"child\") -> make object \"child2\":\"Node\" { }\n}"),
            "the type is open already, so the nested object is empty; ${child.insertText}",
        )
        assertEquals(TYPES.size, offered.size)
        assertParses(inserted(text, child))
    }

    @Test
    fun `directly behind the arrow of a has statement nothing is offered, so nothing sticks to the arrow`() {
        val (document, offset) = model("make object \"node\":\"Node\" {\n    has(\"child\") ->‸\n}\n")

        assertEquals(emptyList(), probe.complete(document, offset).map { it.label })
    }

    @Test
    fun `an object body offers a has statement for each relation it does not have yet, lists always`() {
        val text = "make object \"node\":\"Node\" {\n    has(\"part\") -> make object \"p\":\"Part\" {\n    }\n    ‸\n}\n"
        val offered = templates(text)

        assertEquals(
            listOf(
                "has(\"child\") -> make object \"child\":\"Node\" { … }",
                "has(\"parts\") -> make object \"parts\":\"Part\" { … }",
            ),
            offered.map { it.label },
        )
        val parts = offered.last()
        assertEquals("has(\"parts\") -> make object \"parts\":\"Part\" {\n    prop(\"label\") -> \"\"\n}", parts.insertText)
        assertEquals(listOf("parts", ""), placeholders(parts))
        offered.forEach { assertParses(inserted(text, it)) }
    }

    @Test
    fun `templates rank after the one-line snippets`() {
        val (document, offset) = model("make object \"node\":\"Node\" {\n    ‸\n}\n")
        val labels = probe.labels(document, offset)

        assertEquals(listOf("has(\"…\") -> make object \"…\":\"…\" { }", "knows(\"…\") -> \"…\"", "prop(\"…\") -> \"…\""), labels.take(3))
        assertTrue(labels.drop(3).all { it.startsWith("has(\"") && it.endsWith("{ … }") })
    }

    @Test
    fun `the object writer of the diagram writes the text of the templates for the domain types of the context`() {
        val writer = KmodelObjectWriter.of(analysis, ExampleProject.pathOf("$DIRECTORY/graph.kmodel"))
        val text = "make object \"node\":\"Node\" {\n}\n‸"
        val top = templates(text).single { it.label.contains("\"Node\"") }

        assertEquals(listOf("Part", "Level4", "Level3", "Level2", "Level1", "Level0", "Node"), writer.types.map { it.name })
        assertEquals(KmodelObjectText("node2", top.insertText), writer.root("Node", setOf("node")))
        val child = templates("make object \"node\":\"Node\" {\n    has(\"child\") -> ‸\n}\n").first()
        assertEquals(child.insertText, writer.contained("child", "Node", setOf("node")).text)
        val outside = ExampleProject.analyze(emptyList())
        assertEquals(emptyList(), KmodelObjectWriter.of(outside, ExampleProject.pathOf("$DIRECTORY/graph.kmodel")).types)
    }

    @Test
    fun `without types there is one generic template`() {
        val outside = ServicesProbe(ExampleProject.analyze(emptyList()))
        val path = ExampleProject.pathOf("$DIRECTORY/elsewhere/loose.kmodel")
        val top = ExampleProject.document(path, "")
        val bodyText = "make object \"a\":\"Unknown\" {\n    \n}\n"
        val body = ExampleProject.document(path, bodyText)

        val generic = outside.complete(top, 0).single { it.detail == "template" }
        assertEquals("make object \"name\":\"Type\" {\n    prop(\"key\") -> \"\"\n}", generic.insertText)
        assertEquals(listOf("name", "Type", "key", ""), placeholders(generic))
        assertParses(generic.insertText)

        val genericHas = outside.complete(body, bodyText.indexOf("    \n") + 4).single { it.detail == "template" }
        assertEquals("has(\"key\") -> make object \"name\":\"Type\" {\n    prop(\"key\") -> \"\"\n}", genericHas.insertText)
        assertEquals(listOf("key", "name", "Type", "key", ""), placeholders(genericHas))
        assertParses(bodyText.replace("    \n", "    ${genericHas.insertText}\n"))
    }

    private companion object {
        const val DIRECTORY = "karpfen-dsl-tools/templates"

        /** The types of the metamodel. */
        val TYPES = listOf("Level0", "Level1", "Level2", "Level3", "Level4", "Node", "Part")

        private fun file(name: String, language: KarpfenLanguage, text: String) = ExampleFile("$DIRECTORY/$name", language, text.trimIndent() + "\n")

        val FILES = listOf(
            file(
                "graph.kmeta",
                KarpfenLanguage.KMETA,
                """
                type "Part" "A part" {
                    prop("label", "string")
                }
                type "Level4" "The deepest level" {
                    prop("x", "number")
                }
                type "Level3" "A level" {
                    has("next", "Level4")
                }
                type "Level2" "A level" {
                    has("next", "Level3")
                }
                type "Level1" "A level" {
                    has("next", "Level2")
                }
                type "Level0" "The first level" {
                    has("next", "Level1")
                }
                type "Node" "A node of a graph" {
                    prop("name", "string")
                    prop("weight", "number")
                    prop("active", "boolean")
                    prop("tags", list("string"))
                    has("part", "Part")
                    has("parts", list("Part"))
                    knows("peer", "Node")
                    knows("peers", list("Node"))
                    has("child", "Node")
                }
                """,
            ),
            file("graph.kmodel", KarpfenLanguage.KMODEL, "make object \"node\":\"Node\" {\n}"),
        )
    }
}
