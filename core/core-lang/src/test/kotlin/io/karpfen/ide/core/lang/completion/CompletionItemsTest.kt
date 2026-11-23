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
package io.karpfen.ide.core.lang.completion

import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers snippet and name items: insertion text, caret placement and ranking. */
class CompletionItemsTest {

    private val range = TextRange(3, 5)

    @Test
    fun `a snippet places the caret at its marker and inserts the template without it`() {
        val item = Snippet("STATE \"…\" { }", "STATE \"\$0\" { }", detail = "state").item(range)

        assertEquals("STATE \"\" { }", item.insertText)
        assertEquals(7, item.caretOffsetInInsert)
        assertEquals(range, item.replaceRange)
        assertEquals(CompletionKind.SNIPPET, item.kind)
        assertEquals("state", item.detail)
    }

    @Test
    fun `a keyword puts the caret after it`() {
        val item = Snippet.keyword("NOT LOOPING").item(range)

        assertEquals("NOT LOOPING", item.insertText)
        assertNull(item.caretOffsetInInsert)
        assertEquals(CompletionKind.KEYWORD, item.kind)
    }

    @Test
    fun `placeholder markers become ranges visited by number, and the caret marker stays the end`() {
        val item = Snippet("t", "a(\"\${2:second}\", \"\${1:first}\") \${3:}\$0 \$(path) \${x:y}").item(range)

        assertEquals("a(\"second\", \"first\")  \$(path) \${x:y}", item.insertText)
        assertEquals(
            listOf("first", "second", ""),
            item.placeholders.map { item.insertText.substring(it.start, it.end) },
            "visited by number, an empty placeholder included",
        )
        assertEquals(TextRange(21, 21), item.placeholders.last())
        assertEquals(21, item.caretOffsetInInsert)
    }

    @Test
    fun `a template joins its lines, is marked as template and ranks after the one-line snippet`() {
        val oneLine = Snippet("STATE \"…\" { }", "STATE \"\$0\" { }").item(range)
        val template = Snippet.template("STATE \"…\" with DO", "STATE \"\${1:Name}\" {", "    DO {", "    }", "}").item(range)

        assertEquals("STATE \"Name\" {\n    DO {\n    }\n}", template.insertText)
        assertEquals(listOf(TextRange(7, 11)), template.placeholders)
        assertNull(template.caretOffsetInInsert, "the end of the template is the end of the insertion")
        assertEquals("template", template.detail)
        assertEquals(CompletionKind.SNIPPET, template.kind)
        assertEquals(listOf(oneLine, template), listOf(template, oneLine).sortedBy { it.sortKey })
        assertEquals(emptyList(), oneLine.placeholders)
    }

    @Test
    fun `the builder of a template text records its pieces in the order written`() {
        val text = TemplateTextBuilder().literal("make object \"").placeholder("lamp").literal("\":\"Lamp\" { ").caret().literal("}").build()

        assertEquals("make object \"lamp\":\"Lamp\" { }", text.text)
        assertEquals(listOf(TextRange(13, 17)), text.placeholders)
        assertEquals(28, text.caret)
    }

    @Test
    fun `names are quoted outside strings and ranked by sort key`() {
        val quoted = nameItem("Vector", CompletionKind.TYPE, range, quoted = true, rank = 0)
        val plain = nameItem("Robot", CompletionKind.TYPE, range)

        assertEquals("\"Vector\"", quoted.insertText)
        assertEquals("Robot", plain.insertText)
        assertEquals(listOf(quoted, plain), listOf(plain, quoted).sortedBy { it.sortKey })
        assertEquals(listOf("1:a", "1:b"), listOf(sortKey(1, "B"), sortKey(1, "a")).sorted())
    }
}
