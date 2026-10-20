package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The position-recording JSON scanner behind the descriptor diagnostics. */
class JsonSourceParserTest {

    @Test
    fun `objects, arrays, strings and scalars carry their source ranges`() {
        val text = " {\"a\": [1, true, \"x\\\"y\"], \"b\": {\"c\": null}} "

        val root = assertIs<JsonNode.ObjectNode>(JsonSourceParser(text).parse())

        assertEquals(TextRange(1, text.length - 1), root.range)
        assertEquals(listOf("a", "b"), root.members.map { it.key })
        assertEquals(TextRange(3, 4), root.members[0].keyRange)
        val array = assertIs<JsonNode.ArrayNode>(root.member("a")?.value)
        assertEquals(listOf("1", "true", "\"x\\\"y\""), array.items.map { text.substring(it.range.start, it.range.end) })
        val string = assertIs<JsonNode.StringNode>(array.items[2])
        assertEquals("x\"y", string.value)
        assertEquals("x\\\"y", text.substring(string.content.start, string.content.end))
        val nested = assertIs<JsonNode.ObjectNode>(root.member("b")?.value)
        assertIs<JsonNode.ScalarNode>(nested.member("c")?.value)
        assertNull(root.member("missing"))
    }

    @Test
    fun `escapes are decoded`() {
        val node = assertIs<JsonNode.StringNode>(JsonSourceParser("\"\\u00e4\\n\\t\\/\\\\\"").parse())

        assertEquals("ä\n\t/\\", node.value)
    }

    @Test
    fun `empty containers and unreadable input`() {
        assertEquals(emptyList(), assertIs<JsonNode.ObjectNode>(JsonSourceParser("{ }").parse()).members)
        assertEquals(emptyList(), assertIs<JsonNode.ArrayNode>(JsonSourceParser("[ ]").parse()).items)
        listOf("", "{", "{\"a\" 1}", "[1,", "\"open", "{} {}", "\"\\u12\"", "[".repeat(500) + "]".repeat(500)).forEach {
            assertNull(JsonSourceParser(it).parse(), it)
        }
    }
}
