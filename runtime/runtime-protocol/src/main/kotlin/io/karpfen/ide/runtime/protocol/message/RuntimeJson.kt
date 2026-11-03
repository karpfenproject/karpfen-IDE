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
package io.karpfen.ide.runtime.protocol.message

/** What [RuntimeJson.read] made of a text. */
public sealed interface JsonReading {
    /**
     * The text is one JSON value.
     *
     * @property value the value.
     */
    public data class Read(public val value: WireValue) : JsonReading

    /**
     * The text is not JSON as the runtime writes it.
     *
     * @property offset the offset in the text at which reading failed.
     * @property reason what was found there, in words.
     */
    public data class Malformed(public val offset: Int, public val reason: String) : JsonReading
}

/**
 * The runtime's JSON: reading it strictly and quoting strings by the runtime writer's rule.
 *
 * The runtime writes valid JSON except for the non-finite numbers, which it writes as the bare literals `NaN`,
 * `Infinity` and `-Infinity`. [read] accepts exactly that and nothing more, so drift in the runtime's writers shows as a
 * [JsonReading.Malformed] instead of being guessed around. The runtime's JSON comes from three writers: its hand-written
 * envelope, payload and object writers, org.json for the `stateChange` and `domainEvent` payloads and the environment
 * list, and the snapshot writer.
 */
public object RuntimeJson {

    /**
     * Reads [text] as one JSON value (RFC 8259) plus the runtime's bare `NaN`, `Infinity` and `-Infinity`.
     *
     * Whitespace between tokens is space, tab, carriage return and line feed. Strings take every standard escape,
     * `\uXXXX` and surrogate pairs included, and no unescaped control character. Numbers have the JSON form
     * (`-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`) and are read as [Double]. Objects keep their fields in order,
     * repeated names included. Comments, unquoted names, single quotes, trailing commas, leading zeros and anything after
     * the value are malformed, as is nesting deeper than [MAX_DEPTH] arrays and objects, the limit RFC 8259 allows a
     * reader to set, which keeps the reader's recursion off the bottom of the stack. Never throws.
     */
    public fun read(text: String): JsonReading = JsonTextReader(text).read()

    /**
     * [text] as a JSON string literal by the runtime writer's rule: in quotes, with `"`, `\` and the control characters
     * U+0000 to U+001F escaped (`\b`, `\f`, `\n`, `\r` and `\t` by their short forms, the others as `\u00XX` with
     * lower-case digits) and every other character as it is.
     */
    public fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') appendUnicodeEscape(char) else append(char)
            }
        }
        append('"')
    }

    /** The deepest nesting of arrays and objects that [read] accepts. */
    internal const val MAX_DEPTH: Int = 512

    /** The radix of a `\uXXXX` escape. */
    private const val HEX = 16

    /** The number of digits of a `\uXXXX` escape. */
    private const val UNICODE_ESCAPE_DIGITS = 4

    /** Appends [char] as `\uXXXX` with lower-case hexadecimal digits. */
    private fun StringBuilder.appendUnicodeEscape(char: Char) {
        append("\\u").append(char.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
    }
}

/**
 * One pass of [RuntimeJson.read] over [text]: a recursive-descent reader that stops at the first offset at which the
 * text departs from the grammar.
 */
internal class JsonTextReader(private val text: String) {

    /** The offset of the next character to read. */
    private var position = 0

    /** Thrown inside the reader at the first malformed offset; [read] turns it into a [JsonReading.Malformed]. */
    private class MalformedText(val offset: Int, val reason: String) : RuntimeException(reason, null, false, false)

    /** Reads the whole text as one value. */
    fun read(): JsonReading =
        try {
            skipWhitespace()
            val value = readValue(depth = 0)
            skipWhitespace()
            if (position < text.length) fail("content after the JSON value")
            JsonReading.Read(value)
        } catch (malformed: MalformedText) {
            JsonReading.Malformed(malformed.offset, malformed.reason)
        }

    /** Reads the value at [position], which lies [depth] arrays and objects deep. */
    private fun readValue(depth: Int): WireValue {
        if (position >= text.length) fail("the text ends where a value is expected")
        return when (val char = text[position]) {
            '{' -> readObject(depth + 1)
            '[' -> readArray(depth + 1)
            '"' -> WireValue.WireString(readString())
            't' -> literal("true", WireValue.WireBoolean(true))
            'f' -> literal("false", WireValue.WireBoolean(false))
            'n' -> literal("null", WireValue.WireNull)
            'N' -> literal("NaN", WireValue.WireNumber(Double.NaN))
            'I' -> literal("Infinity", WireValue.WireNumber(Double.POSITIVE_INFINITY))
            '-' -> if (text.startsWith("-Infinity", position)) {
                literal("-Infinity", WireValue.WireNumber(Double.NEGATIVE_INFINITY))
            } else {
                readNumber()
            }
            in '0'..'9' -> readNumber()
            else -> fail("unexpected character '${printable(char)}' where a value is expected")
        }
    }

    /** Reads an object whose `{` is at [position] and which lies [depth] deep. */
    private fun readObject(depth: Int): WireValue.WireObject {
        checkDepth(depth)
        position++
        val fields = ArrayList<WireField>()
        skipWhitespace()
        if (peek() == '}') {
            position++
            return WireValue.WireObject(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a member name in quotes")
            val name = readString()
            skipWhitespace()
            expect(':', "expected ':' after a member name")
            skipWhitespace()
            fields += WireField(name, readValue(depth))
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                '}' -> {
                    position++
                    return WireValue.WireObject(fields)
                }
                else -> fail("expected ',' or '}' after a member")
            }
        }
    }

    /** Reads an array whose `[` is at [position] and which lies [depth] deep. */
    private fun readArray(depth: Int): WireValue.WireArray {
        checkDepth(depth)
        position++
        val items = ArrayList<WireValue>()
        skipWhitespace()
        if (peek() == ']') {
            position++
            return WireValue.WireArray(items)
        }
        while (true) {
            skipWhitespace()
            items += readValue(depth)
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                ']' -> {
                    position++
                    return WireValue.WireArray(items)
                }
                else -> fail("expected ',' or ']' after an element")
            }
        }
    }

    /** Reads a string whose opening quote is at [position] and answers its text with every escape resolved. */
    private fun readString(): String {
        position++
        val result = StringBuilder()
        while (true) {
            if (position >= text.length) fail("the text ends inside a string")
            when (val char = text[position]) {
                '"' -> {
                    position++
                    return result.toString()
                }
                '\\' -> result.append(readEscape())
                else -> {
                    if (char < ' ') fail("unescaped control character '${printable(char)}' in a string")
                    result.append(char)
                    position++
                }
            }
        }
    }

    /** Reads the escape whose backslash is at [position] and answers the character it stands for. */
    private fun readEscape(): Char {
        val start = position
        position++
        if (position >= text.length) fail("the text ends inside an escape")
        val escaped = text[position++]
        return when (escaped) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val digits = text.substring(position, minOf(position + UNICODE_DIGITS, text.length))
                if (digits.length < UNICODE_DIGITS || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    position = start
                    fail("expected four hexadecimal digits after \\u")
                }
                position += UNICODE_DIGITS
                digits.toInt(HEX).toChar()
            }
            else -> {
                position = start
                fail("unknown escape '\\${printable(escaped)}'")
            }
        }
    }

    /** Reads a number in the JSON form at [position]. */
    private fun readNumber(): WireValue.WireNumber {
        val start = position
        if (peek() == '-') position++
        when {
            peek() == '0' -> position++
            peek() in '1'..'9' -> skipDigits()
            else -> fail("expected a digit")
        }
        if (peek() in '0'..'9') fail("a number must not have leading zeros")
        if (peek() == '.') {
            position++
            if (peek() !in '0'..'9') fail("expected a digit after the decimal point")
            skipDigits()
        }
        if (peek() == 'e' || peek() == 'E') {
            position++
            if (peek() == '+' || peek() == '-') position++
            if (peek() !in '0'..'9') fail("expected a digit in the exponent")
            skipDigits()
        }
        return WireValue.WireNumber(text.substring(start, position).toDouble())
    }

    /** Skips the digits at [position]. */
    private fun skipDigits() {
        while (peek() in '0'..'9') position++
    }

    /** Reads the keyword [word] at [position] as [value]. */
    private fun <T : WireValue> literal(word: String, value: T): T {
        if (!text.startsWith(word, position)) fail("unexpected text where a value is expected")
        position += word.length
        return value
    }

    /** Fails unless [depth] is within [RuntimeJson.MAX_DEPTH]. */
    private fun checkDepth(depth: Int) {
        if (depth > RuntimeJson.MAX_DEPTH) fail("arrays and objects nest deeper than ${RuntimeJson.MAX_DEPTH}")
    }

    /** Consumes [char] at [position] or fails with [reason]. */
    private fun expect(char: Char, reason: String) {
        if (peek() != char) fail(reason)
        position++
    }

    /** Skips the whitespace JSON allows between tokens. */
    private fun skipWhitespace() {
        while (position < text.length && text[position] in WHITESPACE) position++
    }

    /** The character at [position], or [END] at the end of the text. */
    private fun peek(): Char = if (position < text.length) text[position] else END

    /** Stops reading: the text is malformed at [position]. */
    private fun fail(reason: String): Nothing = throw MalformedText(position, reason)

    /** [char] as a failure reason shows it: a control character by its code. */
    private fun printable(char: Char): String = if (char < ' ') "U+%04X".format(char.code) else char.toString()

    private companion object {
        /** The whitespace of JSON: space, tab, line feed and carriage return. */
        const val WHITESPACE = " \t\n\r"

        /** Stands for the end of the text; it never occurs in a valid JSON text outside a string. */
        const val END = '\u0000'

        /** The number of digits of a `\uXXXX` escape. */
        const val UNICODE_DIGITS = 4

        /** The radix of a `\uXXXX` escape. */
        const val HEX = 16
    }
}
