package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.text.TextRange

/**
 * A JSON value with its position in the source text, for diagnostics with ranges (kotlinx.serialization's
 * `JsonElement` carries none). Built by [JsonSourceParser]; immutable.
 */
internal sealed interface JsonNode {

    /** The whole value in the source text (for strings including the quotes). */
    val range: TextRange

    /** An object; [members] in document order (duplicate keys are kept). */
    data class ObjectNode(override val range: TextRange, val members: List<Member>) : JsonNode {
        /** The first member named [key], or null. */
        fun member(key: String): Member? = members.firstOrNull { it.key == key }
    }

    /** An array and its [items] in document order. */
    data class ArrayNode(override val range: TextRange, val items: List<JsonNode>) : JsonNode

    /** A string: its decoded [value] and the [content] range between the quotes. */
    data class StringNode(override val range: TextRange, val value: String, val content: TextRange) : JsonNode

    /** A number, `true`, `false` or `null`. */
    data class ScalarNode(override val range: TextRange) : JsonNode

    /** An object member: the decoded [key], its range inside the quotes ([keyRange]) and its [value]. */
    data class Member(val key: String, val keyRange: TextRange, val value: JsonNode)
}

/**
 * A small recursive-descent JSON scanner that records positions (RFC 8259 syntax: objects, arrays, strings with
 * escapes, numbers, literals). The descriptor reader validates and decodes with kotlinx.serialization first and only
 * then asks this scanner for positions, so it does not report syntax errors: on input it cannot read it answers null.
 * Never throws.
 */
internal class JsonSourceParser(private val text: String) {

    private var position = 0

    /** The root value of the text, or null if the text is not a single JSON value. */
    fun parse(): JsonNode? =
        try {
            skipWhitespace()
            val root = value(depth = 0)
            skipWhitespace()
            root.takeIf { position == text.length }
        } catch (e: UnreadableJson) {
            null
        }

    private fun value(depth: Int): JsonNode {
        if (depth > MAX_DEPTH) throw UnreadableJson()
        return when (peek()) {
            '{' -> objectNode(depth)
            '[' -> arrayNode(depth)
            '"' -> stringNode()
            else -> scalarNode()
        }
    }

    private fun objectNode(depth: Int): JsonNode.ObjectNode {
        val start = position++
        val members = ArrayList<JsonNode.Member>()
        skipWhitespace()
        if (peek() == '}') return JsonNode.ObjectNode(TextRange(start, ++position), members)
        while (true) {
            skipWhitespace()
            val key = stringNode()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            members += JsonNode.Member(key.value, key.content, value(depth + 1))
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return JsonNode.ObjectNode(TextRange(start, position), members)
                else -> throw UnreadableJson()
            }
        }
    }

    private fun arrayNode(depth: Int): JsonNode.ArrayNode {
        val start = position++
        val items = ArrayList<JsonNode>()
        skipWhitespace()
        if (peek() == ']') return JsonNode.ArrayNode(TextRange(start, ++position), items)
        while (true) {
            skipWhitespace()
            items += value(depth + 1)
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return JsonNode.ArrayNode(TextRange(start, position), items)
                else -> throw UnreadableJson()
            }
        }
    }

    private fun stringNode(): JsonNode.StringNode {
        val start = position
        expect('"')
        val decoded = StringBuilder()
        while (true) {
            when (val char = next()) {
                '"' -> return JsonNode.StringNode(TextRange(start, position), decoded.toString(), TextRange(start + 1, position - 1))
                '\\' -> decoded.append(escape())
                else -> decoded.append(char)
            }
        }
    }

    private fun escape(): Char = when (val char = next()) {
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            if (position + 4 > text.length) throw UnreadableJson()
            val code = text.substring(position, position + 4).toIntOrNull(16) ?: throw UnreadableJson()
            position += 4
            code.toChar()
        }
        else -> char
    }

    private fun scalarNode(): JsonNode.ScalarNode {
        val start = position
        while (position < text.length && text[position] !in SCALAR_TERMINATORS) position++
        if (position == start) throw UnreadableJson()
        return JsonNode.ScalarNode(TextRange(start, position))
    }

    private fun skipWhitespace() {
        while (position < text.length && text[position] in WHITESPACE) position++
    }

    private fun peek(): Char = if (position < text.length) text[position] else throw UnreadableJson()

    private fun next(): Char = peek().also { position++ }

    private fun expect(char: Char) {
        if (next() != char) throw UnreadableJson()
    }

    /** Internal control flow: the text is not readable JSON. */
    private class UnreadableJson : RuntimeException(null, null, false, false)

    private companion object {
        /** Deeper nesting is not a descriptor; also keeps the recursion far from the stack limit. */
        private const val MAX_DEPTH = 200
        private const val WHITESPACE = " \t\n\r"
        private const val SCALAR_TERMINATORS = " \t\n\r,:]}[{\""
    }
}
