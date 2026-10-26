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
package io.karpfen.ide.core.document.json

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the tolerant JSON parser on concrete texts: the tree with its ranges for valid JSON, and for each recovery rule
 * the error it reports and the tree it keeps.
 */
class JsonParserTest {

    private fun String.at(range: TextRange): String = substring(range.start, range.end)

    /** The root object of [text]. */
    private fun rootOf(text: String): JsonNode.ObjectNode = assertIs<JsonNode.ObjectNode>(JsonParser.parse(text).root)

    /** The PARSER messages of [text] with the text they are reported at. */
    private fun syntaxErrorsOf(text: String): List<Pair<String, String>> =
        JsonParser.parse(text).diagnostics.filter { it.source == DiagnosticSource.PARSER }.map { text.at(it.range) to it.message }

    @Test
    fun `objects, arrays, strings and scalars carry their source ranges`() {
        val text = " {\"a\": [1, true, \"x\\\"y\"], \"b\": {\"c\": null}} "

        val document = JsonParser.parse(text)

        assertEquals(emptyList(), document.diagnostics)
        assertTrue(document.isSyntaxClean)
        val root = assertIs<JsonNode.ObjectNode>(document.root)
        assertEquals(TextRange(1, text.length - 1), root.range)
        assertTrue(root.isClosed)
        assertEquals(listOf("a", "b"), root.members.map { it.key.value })
        assertEquals(TextRange(3, 4), root.members[0].key.content)
        val array = assertIs<JsonNode.ArrayNode>(root.member("a")?.value)
        assertEquals(listOf("1", "true", "\"x\\\"y\""), array.items.map { text.at(it.range) })
        val string = assertIs<JsonNode.StringNode>(array.items[2])
        assertEquals("x\"y", string.value)
        assertEquals("x\\\"y", text.at(string.content))
        assertEquals(1L, assertIs<JsonNode.NumberNode>(array.items[0]).wholeNumber)
        val nested = assertIs<JsonNode.ObjectNode>(root.member("b")?.value)
        assertEquals(JsonLiteral.NULL, assertIs<JsonNode.LiteralNode>(nested.member("c")?.value).literal)
        assertNull(root.member("missing"))
        assertEquals("\"a\": [1, true, \"x\\\"y\"]", text.at(root.members[0].range))
    }

    @Test
    fun `a repeated key keeps both members and is looked up like a JSON reader decodes it, the last one`() {
        val root = rootOf("{\"k\": 1, \"k\": \"two\"}")

        assertEquals(listOf("k", "k"), root.members.map { it.key.value })
        assertEquals("two", assertIs<JsonNode.StringNode>(root.member("k")?.value).value)
    }

    @Test
    fun `a missing comma is reported at the next member, which is still read`() {
        val text = "{\"a\": 1\n \"b\": [2 3]}"

        assertEquals(listOf("\"b\"" to "Expected ',' before this member.", "3" to "Expected ',' before this value."), syntaxErrorsOf(text))
        val root = rootOf(text)
        assertEquals(listOf("a", "b"), root.members.map { it.key.value })
        assertEquals(2, assertIs<JsonNode.ArrayNode>(root.member("b")?.value).items.size)
    }

    @Test
    fun `a missing value is reported at the colon and leaves the member without value`() {
        val text = "{\"model\": , \"events\": \"EVENTS.kmeta\"}"

        assertEquals(listOf(":" to "Expected a value after ':'."), syntaxErrorsOf(text))
        val root = rootOf(text)
        assertNull(root.member("model")?.value)
        assertEquals(TextRange(8, 9), root.member("model")?.colon)
        assertIs<JsonNode.StringNode>(root.member("events")?.value)
        assertTrue(root.isClosed)
    }

    @Test
    fun `a missing colon is reported at the key and a following value is still the key's`() {
        val text = "{\"model\" \"robot.kmodel\", \"a\"\n\"b\": 1}"

        assertEquals(
            listOf(
                "\"model\"" to "Expected ':' after the key.",
                "\"a\"" to "Expected ':' after the key.",
                "\"b\"" to "Expected ',' before this member.",
            ),
            syntaxErrorsOf(text),
        )
        val root = rootOf(text)
        assertEquals("robot.kmodel", assertIs<JsonNode.StringNode>(root.member("model")?.value).value)
        assertNull(root.member("model")?.colon)
        assertNull(root.member("a")?.value, "a string followed by ':' starts the next member")
        assertEquals(1L, assertIs<JsonNode.NumberNode>(root.member("b")?.value).wholeNumber)
    }

    @Test
    fun `trailing and extra commas are errors at the comma`() {
        assertEquals(listOf("," to "Remove the ',' before '}'; JSON allows no trailing comma."), syntaxErrorsOf("{\"a\": 1,}"))
        assertEquals(listOf("," to "Remove the ',' before ']'; JSON allows no trailing comma."), syntaxErrorsOf("[1,]"))
        assertEquals(listOf("," to "Remove the extra ','."), syntaxErrorsOf("[1,,2]"))
        assertEquals(listOf("," to "Expected a member before ','."), syntaxErrorsOf("{,}"))
    }

    @Test
    fun `the end of the text is one error after the last token and keeps everything read so far`() {
        val text = "{\"metamodel\": \"a.kmeta\", \"stateMachines\": [{\"file\": \"m.kstates\", \"model\": "

        val document = JsonParser.parse(text)

        assertEquals(listOf(TextRange.empty(text.trimEnd().length)), document.diagnostics.map { it.range })
        assertEquals("Expected a value after ':'.", document.diagnostics.single().message)
        val root = assertIs<JsonNode.ObjectNode>(document.root)
        assertFalse(root.isClosed)
        val machines = assertIs<JsonNode.ArrayNode>(root.member("stateMachines")?.value)
        assertFalse(machines.isClosed)
        val entry = assertIs<JsonNode.ObjectNode>(machines.items.single())
        assertEquals(listOf("file", "model"), entry.members.map { it.key.value })
        assertEquals(TextRange(0, text.trimEnd().length), root.range)
    }

    @Test
    fun `a closing bracket of an enclosing container ends the inner one`() {
        val text = "{\"a\": [1, 2}"

        assertEquals(listOf("}" to "Expected ']' to close the list before '}'."), syntaxErrorsOf(text))
        val root = rootOf(text)
        assertTrue(root.isClosed)
        val array = assertIs<JsonNode.ArrayNode>(root.member("a")?.value)
        assertFalse(array.isClosed)
        assertEquals("[1, 2", text.at(array.range))
    }

    @Test
    fun `stray brackets, keys and content after the root are reported`() {
        assertEquals(listOf("]" to "Unexpected ']' after the end of the JSON value."), syntaxErrorsOf("[1]]"))
        assertEquals(listOf("[" to "Unexpected '[' after the end of the JSON value."), syntaxErrorsOf("{} []"))
        assertEquals(listOf("1" to "Expected a key in double quotes."), syntaxErrorsOf("{1}"))
        assertEquals(listOf(":" to "Unexpected ':' in a list."), syntaxErrorsOf("[:]"))
    }

    @Test
    fun `a stray token before the root is reported once and the root is still read`() {
        val word = "x {\"metamodel\": \"a.kmeta\",\n \"model\": \"b\"}"
        val wordDocument = JsonParser.parse(word)
        assertEquals(listOf("x" to DiagnosticSource.LEXER), wordDocument.diagnostics.map { word.at(it.range) to it.source })
        assertEquals(listOf("metamodel", "model"), assertIs<JsonNode.ObjectNode>(wordDocument.root).members.map { it.key.value })

        val closer = "}{\"metamodel\": \"a.kmeta\"}"
        assertEquals(listOf("}" to "Unexpected '}' before the JSON value."), syntaxErrorsOf(closer))
        assertEquals(listOf("metamodel"), rootOf(closer).members.map { it.key.value })
        assertEquals(
            listOf("]" to "Unexpected ']' before the JSON value.", ":" to "Unexpected ':' before the JSON value."),
            syntaxErrorsOf("] : [1]"),
        )
    }

    @Test
    fun `a stray value before the root object is reported once and the object is still the root`() {
        listOf("1", "true\n", "\"\"\n", "\"x\" 2 ").forEach { stray ->
            val text = stray + "{\"metamodel\": \"a.kmeta\",\n \"events\": [\"e\"]}"

            val expected = stray.trim().split(" ").map { it to "Unexpected '$it' before the JSON value." }
            assertEquals(expected, syntaxErrorsOf(text), "'$stray'")
            val root = rootOf(text)
            assertEquals(listOf("metamodel", "events"), root.members.map { it.key.value }, "'$stray'")
            assertTrue(root.isClosed, "'$stray'")
        }
    }

    @Test
    fun `a quote typed before the brace makes it part of a string, and the members after it are still the root`() {
        val text = "\"{\n \"metamodel\": \"a.kmeta\",\n \"events\": [\"e\"]\n}"

        val document = JsonParser.parse(text)

        assertEquals(
            listOf(DiagnosticSource.LEXER, DiagnosticSource.PARSER).map { "\"{" to it },
            document.diagnostics.map { text.at(it.range) to it.source }.sortedBy { it.second },
        )
        assertEquals(listOf("\"{" to "Unexpected '\"{' before the JSON value."), syntaxErrorsOf(text))
        val root = assertIs<JsonNode.ObjectNode>(document.root)
        assertEquals(listOf("metamodel", "events"), root.members.map { it.key.value })
        assertEquals("\"metamodel\": \"a.kmeta\",\n \"events\": [\"e\"]", text.at(root.range))
        assertFalse(root.isClosed, "no '{' pairs with the '}'")
    }

    @Test
    fun `members without the opening brace are read as the root object, and the missing brace is one error`() {
        val text = "\"metamodel\": \"a.kmeta\",\n\"model\": \"b\"\n}"

        assertEquals(listOf("\"metamodel\"" to "Expected '{' before this member."), syntaxErrorsOf(text))
        val root = rootOf(text)
        assertEquals(listOf("metamodel", "model"), root.members.map { it.key.value })
        assertFalse(root.isClosed)
        assertEquals(listOf("\"x\"" to "Unexpected '\"x\"' before the JSON value."), syntaxErrorsOf("\"x\"\n\"a\": 1}"))
    }

    @Test
    fun `a scalar stays the root when no object, list or member follows it`() {
        assertEquals("abc", assertIs<JsonNode.StringNode>(JsonParser.parse("\"abc\"").root).value)
        assertEquals(emptyList(), JsonParser.parse(" 12 ").diagnostics)
        assertEquals(listOf("2" to "Unexpected '2' after the end of the JSON value."), syntaxErrorsOf("1 2"))
        assertIs<JsonNode.NumberNode>(JsonParser.parse("1 2").root)
    }

    @Test
    fun `texts without a value are one error and no root`() {
        listOf("", "   \n").forEach { text ->
            val document = JsonParser.parse(text)
            assertNull(document.root)
            assertEquals(listOf("Expected a JSON value."), document.diagnostics.map { it.message }, "'$text'")
        }
        assertNull(JsonParser.parse("}").root)
        assertEquals(listOf("}" to "Expected a JSON value."), syntaxErrorsOf("}"))
        assertEquals(listOf("]" to "Expected a JSON value."), syntaxErrorsOf("] }"), "one error for the whole text")
        assertEquals(listOf(DiagnosticSource.LEXER), JsonParser.parse("x").diagnostics.map { it.source }, "the lexer's error only")
    }

    @Test
    fun `bare words are reported by the lexer only, also as unquoted keys`() {
        val text = "{name: \"x\", \"model\": cleaning, \"b\": 2}"

        val document = JsonParser.parse(text)

        assertEquals(listOf("name", "cleaning"), document.diagnostics.map { text.at(it.range) })
        assertTrue(document.diagnostics.all { it.source == DiagnosticSource.LEXER && it.code == DiagnosticCodes.LEXER_ERROR })
        val root = assertIs<JsonNode.ObjectNode>(document.root)
        assertEquals(listOf("model", "b"), root.members.map { it.key.value })
        assertNull(root.member("model")?.value)
    }

    @Test
    fun `nesting beyond the limit is one error and no stack overflow`() {
        val text = "[".repeat(5_000) + "]".repeat(5_000)

        val document = JsonParser.parse(text)

        assertIs<JsonNode.ArrayNode>(document.root)
        assertEquals(1, document.diagnostics.size, "${document.diagnostics}")
        assertTrue("nested" in document.diagnostics.single().message)
    }

    @Test
    fun `token lookups find the token at and before an offset`() {
        val text = "{\"a\": 1}"
        val document = JsonParser.parse(text)

        assertEquals(JsonTokenKind.STRING, document.tokenAt(2)?.kind)
        assertNull(document.tokenAt(5), "whitespace")
        assertEquals(JsonTokenKind.NAME_SEPARATOR, document.tokenBefore(5)?.kind)
        assertEquals(JsonTokenKind.BEGIN_OBJECT, document.tokenBefore(1)?.kind)
        assertNull(document.tokenBefore(0))
    }
}
