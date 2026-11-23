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
package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the KMeta templates of whole types: their text, the placeholders in visiting order, the event metamodel case,
 * the ranking after the one-line snippet, and that dsl-tools reads them without findings once inserted.
 */
class KmetaTemplatesTest {

    private val probe = ServicesProbe()
    private val reader = DslReader()

    /** The one template at the end of the example at [examplePath], which is a top-level position. */
    private fun templateAtEnd(examplePath: String): CompletionItem {
        val document = ExampleProject.example(examplePath)
        val items = probe.complete(document, document.text.length)
        assertEquals(listOf("type \"Name\" \"Description\" { }"), items.filter { it.detail != "template" }.map { it.label })
        assertTrue(items.first().sortKey < items.last().sortKey, "the one-line snippet ranks before the template")
        return items.single { it.detail == "template" }
    }

    /** The texts of the placeholders of [item], in visiting order. */
    private fun placeholders(item: CompletionItem): List<String> = item.placeholders.map { item.insertText.substring(it.start, it.end) }

    @Test
    fun `a domain metamodel offers a whole type with a property, a containment and a reference`() {
        val template = templateAtEnd(ExamplePaths.ROBOT_KMETA)

        assertEquals("type \"Name\" with prop, has and knows", template.label)
        assertEquals(
            "type \"Name\" \"description\" {\n" +
                "    prop(\"name\", \"string\")\n" +
                "    has(\"part\", \"Type\")\n" +
                "    knows(\"other\", \"Type\")\n" +
                "}",
            template.insertText,
        )
        assertEquals(listOf("Name", "description", "name", "string", "part", "Type", "other", "Type"), placeholders(template))
    }

    @Test
    fun `an event metamodel offers a whole event type with what a payload holds`() {
        val template = templateAtEnd(ExamplePaths.ROBOT_EVENTS)

        assertEquals("type \"event\" with prop and has", template.label)
        assertEquals(
            "type \"event\" \"description\" {\n    prop(\"name\", \"string\")\n    has(\"part\", \"Type\")\n}",
            template.insertText,
        )
        assertEquals(listOf("event", "description", "name", "string", "part", "Type"), placeholders(template))
        assertFalse("knows" in template.insertText)
    }

    @Test
    fun `the inserted types read without findings in a domain and in an event metamodel`() {
        val domainTemplate = templateAtEnd(ExamplePaths.ROBOT_KMETA).insertText
        val eventTemplate = templateAtEnd(ExamplePaths.ROBOT_EVENTS).insertText
        val domainText = "type \"Type\" \"A target\" {\n}\n$domainTemplate\ntype \"Root\" \"The root\" {\n}\n"

        val domain = reader.kmeta(TextSnapshot(domainText, 0))
        assertEquals(emptyList(), domain.diagnostics, "the domain metamodel with the template")
        val events = reader.kmeta(TextSnapshot("$eventTemplate\n", 0), domain.model!!.types)
        assertEquals(emptyList(), events.diagnostics, "the event metamodel with the template")
    }

    @Test
    fun `inside a type body no type template is offered`() {
        val document = ExampleProject.example(ExamplePaths.ROBOT_KMETA)
        val offset = after(document.text.text, "type \"Vector\" \"A vector in 2D space\" {\n")

        assertTrue(probe.complete(document, offset).none { it.detail == "template" })
    }
}
