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

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange

/**
 * The tokens of a JSON text and the lexical errors found in it.
 *
 * @property tokens every token in text order; together they cover every character that is not JSON whitespace.
 * @property diagnostics LEXER errors (`lexer-error`), sorted with [Diagnostic.ORDER].
 */
public data class LexedJson(public val tokens: List<JsonToken>, public val diagnostics: List<Diagnostic>)

/**
 * Splits a text into JSON tokens, written for the IDE instead of taken from a JSON library because the editor needs
 * every token with its range and keeps going after every error. Never throws; stateless.
 *
 * Rules (RFC 8259, strict like the runtime's reader, tolerant in what it keeps):
 * - whitespace is space, tab, line feed and carriage return;
 * - a string runs from `"` to the next unescaped `"`; a line break or the end of the text ends an unterminated string
 *   before it (one error on the whole token). Unknown escapes, `\u` escapes with fewer than four hexadecimal digits and
 *   raw control characters are errors inside the token, which stays a [JsonTokenKind.STRING];
 * - a number is the longest run of `0-9 + - . e E` starting with a digit or `-`; a run that is no JSON number (`01`,
 *   `1.`, `-`) is [JsonTokenKind.INVALID];
 * - a word is the longest run of letters, digits and `_`; `true`, `false` and `null` are literals, every other word is
 *   [JsonTokenKind.INVALID] (strings need double quotes);
 * - any other run of characters that start no token is one [JsonTokenKind.INVALID] token.
 *
 * Every INVALID token carries exactly one LEXER error over its range.
 */
public object JsonLexer {

    /** The tokens and lexical errors of [text]. */
    public fun lex(text: String): LexedJson = JsonScanner(text).scan()

    /** True for the whitespace characters of JSON. */
    public fun isWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\n' || char == '\r'
}

/**
 * The scanner behind [JsonLexer]: one instance per text, used once. Implements the rules documented on [JsonLexer].
 */
private class JsonScanner(private val text: String) {

    private var position = 0
    private val tokens = ArrayList<JsonToken>()
    private val diagnostics = ArrayList<Diagnostic>()

    /** Scans the whole text. */
    fun scan(): LexedJson {
        while (position < text.length) {
            val char = text[position]
            when {
                JsonLexer.isWhitespace(char) -> position++
                char == '"' -> string()
                startsNumber(char) -> number()
                isWordChar(char) -> word()
                char in STRUCTURAL -> structural(char)
                else -> other()
            }
        }
        return LexedJson(tokens, diagnostics.sortedWith(Diagnostic.ORDER))
    }

    /** One bracket or separator. */
    private fun structural(char: Char) {
        val kind = when (char) {
            '{' -> JsonTokenKind.BEGIN_OBJECT
            '}' -> JsonTokenKind.END_OBJECT
            '[' -> JsonTokenKind.BEGIN_ARRAY
            ']' -> JsonTokenKind.END_ARRAY
            ':' -> JsonTokenKind.NAME_SEPARATOR
            else -> JsonTokenKind.VALUE_SEPARATOR
        }
        add(kind, position, position + 1)
        position++
    }

    /** A string starting at the current quote, terminated or not. */
    private fun string() {
        val start = position
        position++
        while (true) {
            if (position >= text.length || isLineBreak(text[position])) {
                add(JsonTokenKind.STRING, start, position)
                error(TextRange(start, position), "The string is not closed; add '\"' before the end of the line.")
                return
            }
            val char = text[position]
            when {
                char == '"' -> {
                    position++
                    add(JsonTokenKind.STRING, start, position)
                    return
                }
                char == '\\' -> escape()
                char < ' ' -> {
                    error(TextRange(position, position + 1), "A control character in a string must be written as an escape such as \\t.")
                    position++
                }
                else -> position++
            }
        }
    }

    /**
     * The escape sequence at the current backslash. A backslash at the end of a line or of the text is left for
     * [string], which reports the unterminated string.
     */
    private fun escape() {
        val start = position
        val next = text.getOrNull(position + 1)
        if (next == null || isLineBreak(next)) {
            position++
            return
        }
        when {
            next in SIMPLE_ESCAPES -> position += 2
            next == 'u' -> unicodeEscape(start)
            else -> {
                position += if (next.isHighSurrogate() && text.getOrNull(position + 2)?.isLowSurrogate() == true) 3 else 2
                error(TextRange(start, position), "Unknown escape '\\$next'; JSON knows \\\" \\\\ \\/ \\b \\f \\n \\r \\t and \\u.")
            }
        }
    }

    /** A `\u` escape starting at [start]: four hexadecimal digits, or an error over the digits that are there. */
    private fun unicodeEscape(start: Int) {
        position += 2
        var digits = 0
        while (digits < UNICODE_DIGITS && position < text.length && text[position].digitToIntOrNull(HEX) != null) {
            position++
            digits++
        }
        if (digits < UNICODE_DIGITS) error(TextRange(start, position), "A \\u escape needs four hexadecimal digits.")
    }

    /** A number, or an INVALID token for a run of number characters that is no JSON number. */
    private fun number() {
        val start = position
        while (position < text.length && text[position] in NUMBER_CHARS) position++
        val literal = text.substring(start, position)
        if (NUMBER.matches(literal)) {
            add(JsonTokenKind.NUMBER, start, position)
        } else {
            invalid(start, "'$literal' is no JSON number.")
        }
    }

    /** A literal, or an INVALID token for any other word. */
    private fun word() {
        val start = position
        while (position < text.length && isWordChar(text[position])) position++
        when (text.substring(start, position)) {
            "true" -> add(JsonTokenKind.TRUE, start, position)
            "false" -> add(JsonTokenKind.FALSE, start, position)
            "null" -> add(JsonTokenKind.NULL, start, position)
            else -> invalid(start, "Unknown word '${text.substring(start, position)}'; keys and strings are written in double quotes.")
        }
    }

    /** A run of characters that start no token. */
    private fun other() {
        val start = position
        while (position < text.length && startsNothing(text[position])) position++
        invalid(start, "Unexpected '${text.substring(start, position)}'.")
    }

    /** The INVALID token from [start] to the current position with its LEXER error [message]. */
    private fun invalid(start: Int, message: String) {
        add(JsonTokenKind.INVALID, start, position)
        error(TextRange(start, position), message)
    }

    private fun add(kind: JsonTokenKind, start: Int, end: Int) {
        tokens += JsonToken(kind, TextRange(start, end))
    }

    private fun error(range: TextRange, message: String) {
        diagnostics += Diagnostic(range, Severity.ERROR, message, DiagnosticCodes.LEXER_ERROR, DiagnosticSource.LEXER)
    }

    private fun startsNumber(char: Char): Boolean = char == '-' || char.isAsciiDigit()

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private fun isLineBreak(char: Char): Boolean = char == '\n' || char == '\r'

    /** True for a character that starts no token: no whitespace, quote, number, word or structural character. */
    private fun startsNothing(char: Char): Boolean =
        !JsonLexer.isWhitespace(char) && char != '"' && !startsNumber(char) && !isWordChar(char) && char !in STRUCTURAL

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private companion object {
        /** The brackets and separators. */
        const val STRUCTURAL = "{}[]:,"

        /** The characters after a backslash that form an escape on their own. */
        const val SIMPLE_ESCAPES = "\"\\/bfnrt"

        /** The characters a number token is made of. */
        const val NUMBER_CHARS = "0123456789+-.eE"

        /** The number of hexadecimal digits of a `\u` escape. */
        const val UNICODE_DIGITS = 4

        /** The radix of the digits of a `\u` escape. */
        const val HEX = 16

        /** A number in JSON syntax. */
        val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    }
}
