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
    fun `texts without a value are one error and no root`() {
        listOf("", "   \n").forEach { text ->
            val document = JsonParser.parse(text)
            assertNull(document.root)
            assertEquals(listOf("Expected a JSON value."), document.diagnostics.map { it.message }, "'$text'")
        }
        assertNull(JsonParser.parse("}").root)
        assertEquals(listOf("}" to "Expected a JSON value."), syntaxErrorsOf("}"))
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
