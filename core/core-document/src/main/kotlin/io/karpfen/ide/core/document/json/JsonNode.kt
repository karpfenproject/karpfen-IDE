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

import io.karpfen.ide.core.text.TextRange

/**
 * A JSON value with its position in the source text, as [JsonParser] builds it from text that may be broken. Values
 * that the text lacks are absent, so [Member.value] is null. Containers that the text does not close are marked as
 * such. Nodes are immutable.
 */
public sealed interface JsonNode {

    /**
     * The whole value in the source text. For strings it includes the quotes. For containers it runs from bracket to
     * bracket. A root object whose `{` is missing runs from its first member to the end of its last one.
     */
    public val range: TextRange

    /** An object or an array, which is a value that holds other values between its brackets. */
    public sealed interface Container : JsonNode {
        /**
         * False if the text ends, or a bracket of an enclosing container follows, before the closing bracket. Also
         * false for a root object whose `{` is missing, because it has no bracket to pair. Such a container ends after
         * the last value it holds.
         */
        public val isClosed: Boolean

        /** The values directly inside, in document order: the values of an object's members or an array's items. */
        public val children: List<JsonNode>
    }

    /**
     * An object.
     *
     * @property members the members in document order. A repeated key is kept as often as it occurs.
     */
    public data class ObjectNode(
        override val range: TextRange,
        public val members: List<Member>,
        override val isClosed: Boolean,
    ) : Container {
        override val children: List<JsonNode> get() = members.mapNotNull { it.value }

        /** The last member named [key], or null. A JSON reader decodes the last of repeated keys, too. */
        public fun member(key: String): Member? = members.lastOrNull { it.key.value == key }
    }

    /**
     * An array.
     *
     * @property items the items in document order.
     */
    public data class ArrayNode(
        override val range: TextRange,
        public val items: List<JsonNode>,
        override val isClosed: Boolean,
    ) : Container {
        override val children: List<JsonNode> get() = items
    }

    /**
     * A string.
     *
     * @property value the decoded text. Escapes are resolved, and an invalid escape is kept as written.
     * @property content the range between the quotes, or to the end of an unterminated string.
     * @property isClosed false for a string that a line break or the end of the text ended.
     */
    public data class StringNode(
        override val range: TextRange,
        public val value: String,
        public val content: TextRange,
        public val isClosed: Boolean,
    ) : JsonNode

    /**
     * A number.
     *
     * @property text the number as written.
     */
    public data class NumberNode(override val range: TextRange, public val text: String) : JsonNode {
        /**
         * The number as a whole number of the [Long] range. Null if it has a fraction or an exponent, or if it is too
         * large.
         */
        public val wholeNumber: Long? get() = text.takeIf { WHOLE.matches(it) }?.toLongOrNull()

        private companion object {
            /** A number without fraction and exponent. */
            val WHOLE = Regex("""-?(0|[1-9][0-9]*)""")
        }
    }

    /** `true`, `false` or `null`. */
    public data class LiteralNode(override val range: TextRange, public val literal: JsonLiteral) : JsonNode

    /**
     * An object member.
     *
     * @property key the key as a string node. Its range includes the quotes, and its [StringNode.value] is the name
     *   that [ObjectNode.member] looks up.
     * @property colon the range of the `:` after the key, or null if the text lacks it.
     * @property value the value, or null if the text lacks it or has a token there that is no value.
     */
    public data class Member(public val key: StringNode, public val colon: TextRange?, public val value: JsonNode?) {
        /**
         * From the key to the end of the value. Without a value it ends at the colon, and without a colon it ends at
         * the key.
         */
        public val range: TextRange get() = TextRange(key.range.start, (value?.range ?: colon ?: key.range).end)
    }
}

/**
 * Every container at or below [this] value in document order, with a container before the ones it holds. This is the
 * structure that bracket matching and folding read, whatever the values mean.
 */
public fun JsonNode.containers(): List<JsonNode.Container> =
    ArrayList<JsonNode.Container>().also { collectContainers(this, it) }

/** Adds [node], if it is a container, and every container below it to [into]. */
private fun collectContainers(node: JsonNode, into: MutableList<JsonNode.Container>) {
    if (node !is JsonNode.Container) return
    into += node
    node.children.forEach { collectContainers(it, into) }
}

/** The literals of JSON. */
public enum class JsonLiteral(public val text: String) {
    /** `true`. */
    TRUE("true"),

    /** `false`. */
    FALSE("false"),

    /** `null`. */
    NULL("null"),
}

/** Decoding of the string tokens of [JsonLexer]. */
public object JsonStrings {

    /**
     * True if the string token at [range] of [text] ends with its closing quote. The token must be at least two
     * characters long, and its last quote must be preceded by an even number of backslashes, so it is not an escaped
     * quote.
     */
    public fun isClosed(text: String, range: TextRange): Boolean {
        if (range.length < 2 || text[range.end - 1] != '"') return false
        var backslashes = 0
        var index = range.end - 2
        while (index > range.start && text[index] == '\\') {
            backslashes++
            index--
        }
        return backslashes % 2 == 0
    }

    /**
     * The range between the quotes of the string token at [range]. It runs to the end of a token that is not
     * [isClosed].
     */
    public fun contentOf(text: String, range: TextRange): TextRange =
        TextRange(range.start + 1, if (isClosed(text, range)) range.end - 1 else range.end)

    /**
     * The decoded [content] of [text]. The simple escapes and the complete `\u` escapes are resolved. An invalid escape
     * is kept as written, and the lexer reports it.
     */
    public fun decode(text: String, content: TextRange): String {
        val decoded = StringBuilder(content.length)
        var index = content.start
        while (index < content.end) {
            val char = text[index]
            if (char != '\\' || index + 1 >= content.end) {
                decoded.append(char)
                index++
                continue
            }
            val escaped = text[index + 1]
            val simple = SIMPLE.indexOf(escaped).takeIf { it >= 0 }?.let { RESOLVED[it] }
            val unicode = if (escaped == 'u') unicodeAt(text, index + 2, content.end) else null
            when {
                simple != null -> {
                    decoded.append(simple)
                    index += 2
                }
                unicode != null -> {
                    decoded.append(unicode)
                    index += UNICODE_ESCAPE_LENGTH
                }
                else -> {
                    decoded.append(char)
                    index++
                }
            }
        }
        return decoded.toString()
    }

    /**
     * [value] written as the content of a JSON string. It is the inverse of [decode]. Quotes and backslashes are
     * escaped, control characters become `\u` escapes, and everything else stays as it is.
     */
    public fun encode(value: String): String = buildString(value.length) {
        for (char in value) {
            when {
                char == '"' || char == '\\' -> append('\\').append(char)
                char < ' ' -> append("\\u").append(char.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                else -> append(char)
            }
        }
    }

    /**
     * The character of the four hexadecimal digits at [start] of [text] before [end]. Null if the digits are not there.
     */
    private fun unicodeAt(text: String, start: Int, end: Int): Char? {
        if (start + UNICODE_DIGITS > end) return null
        val digits = text.substring(start, start + UNICODE_DIGITS)
        return if (digits.all { it.digitToIntOrNull(HEX) != null }) digits.toInt(HEX).toChar() else null
    }

    /** The characters after a backslash that form an escape on their own. [RESOLVED] holds what they stand for. */
    private const val SIMPLE = "\"\\/bfnrt"

    /** What the characters of [SIMPLE] stand for, in the same order. */
    private const val RESOLVED = "\"\\/\b\u000C\n\r\t"

    /** The number of hexadecimal digits of a `\u` escape. */
    private const val UNICODE_DIGITS = 4

    /** The length of a whole `\u` escape. */
    private const val UNICODE_ESCAPE_LENGTH = 6

    /** The radix of the digits of a `\u` escape. */
    private const val HEX = 16
}
