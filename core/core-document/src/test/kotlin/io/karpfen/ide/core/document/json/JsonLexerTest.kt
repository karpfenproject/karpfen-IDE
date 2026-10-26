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
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the JSON lexer and tokenizer on concrete texts: token kinds and ranges, and the lexical errors. */
class JsonLexerTest {

    /** The kinds and texts of the tokens of [text]. */
    private fun tokensOf(text: String): List<Pair<JsonTokenKind, String>> =
        JsonLexer.lex(text).tokens.map { it.kind to text.substring(it.range.start, it.range.end) }

    @Test
    fun `a descriptor splits into structural tokens, strings, numbers and literals`() {
        val text = "{ \"a\": [1, -2.5e3, true, false, null], \"b\": \"x\" }"

        assertEquals(
            listOf(
                JsonTokenKind.BEGIN_OBJECT to "{", JsonTokenKind.STRING to "\"a\"", JsonTokenKind.NAME_SEPARATOR to ":",
                JsonTokenKind.BEGIN_ARRAY to "[", JsonTokenKind.NUMBER to "1", JsonTokenKind.VALUE_SEPARATOR to ",",
                JsonTokenKind.NUMBER to "-2.5e3", JsonTokenKind.VALUE_SEPARATOR to ",", JsonTokenKind.TRUE to "true",
                JsonTokenKind.VALUE_SEPARATOR to ",", JsonTokenKind.FALSE to "false", JsonTokenKind.VALUE_SEPARATOR to ",",
                JsonTokenKind.NULL to "null", JsonTokenKind.END_ARRAY to "]", JsonTokenKind.VALUE_SEPARATOR to ",",
                JsonTokenKind.STRING to "\"b\"", JsonTokenKind.NAME_SEPARATOR to ":", JsonTokenKind.STRING to "\"x\"",
                JsonTokenKind.END_OBJECT to "}",
            ),
            tokensOf(text),
        )
        assertEquals(emptyList(), JsonLexer.lex(text).diagnostics)
    }

    @Test
    fun `escapes stay inside their string and only invalid ones are errors`() {
        val text = "\"q\\\" b\\\\ s\\/ \\u00e4 \\x \\u12\""

        val lexed = JsonLexer.lex(text)

        assertEquals(listOf(JsonTokenKind.STRING to text), tokensOf(text))
        assertEquals(listOf("\\x", "\\u12"), lexed.diagnostics.map { text.substring(it.range.start, it.range.end) })
        assertTrue(lexed.diagnostics.all { it.source == DiagnosticSource.LEXER && it.code == DiagnosticCodes.LEXER_ERROR })
    }

    @Test
    fun `an unterminated string ends before the line break and is one error`() {
        val text = "{\"model\": \"robot.km\n}"

        val lexed = JsonLexer.lex(text)

        assertEquals(
            listOf(
                JsonTokenKind.BEGIN_OBJECT to "{", JsonTokenKind.STRING to "\"model\"", JsonTokenKind.NAME_SEPARATOR to ":",
                JsonTokenKind.STRING to "\"robot.km", JsonTokenKind.END_OBJECT to "}",
            ),
            tokensOf(text),
        )
        assertEquals(listOf(TextRange(10, 19)), lexed.diagnostics.map { it.range })
        assertTrue(!JsonStrings.isClosed(text, TextRange(10, 19)))
        assertEquals("robot.km", JsonStrings.decode(text, JsonStrings.contentOf(text, TextRange(10, 19))))
    }

    @Test
    fun `bare words, malformed numbers and unknown characters are invalid tokens with one error each`() {
        val text = "[cleaning, 01, 1., -, True, 'x', #]"

        val lexed = JsonLexer.lex(text)

        val invalid = lexed.tokens.filter { it.kind == JsonTokenKind.INVALID }.map { text.substring(it.range.start, it.range.end) }
        assertEquals(listOf("cleaning", "01", "1.", "-", "True", "'", "x", "'", "#"), invalid)
        assertEquals(invalid.size, lexed.diagnostics.size)
        assertEquals(lexed.tokens.filter { it.kind == JsonTokenKind.INVALID }.map { it.range }, lexed.diagnostics.map { it.range })
    }

    @Test
    fun `control characters inside strings are errors, a tab outside is whitespace`() {
        val text = "\t\"a\tb\""

        val lexed = JsonLexer.lex(text)

        assertEquals(listOf(JsonTokenKind.STRING to "\"a\tb\""), tokensOf(text))
        assertEquals(listOf(TextRange(3, 4)), lexed.diagnostics.map { it.range })
    }

    @Test
    fun `strings decode their escapes, and a quote preceded by an escaped backslash closes the string`() {
        val text = "\"a\\\\\""
        val range = TextRange(0, text.length)

        assertTrue(JsonStrings.isClosed(text, range))
        assertEquals("a\\", JsonStrings.decode(text, JsonStrings.contentOf(text, range)))
        val escapes = "\\u00e4\\n\\t\\/\\\""
        assertEquals("ä\n\t/\"", JsonStrings.decode(escapes, TextRange(0, escapes.length)))
    }

    @Test
    fun `the tokenizer maps JSON tokens to the syntax token kinds of the editor`() {
        val text = "{\"a\": [1, true, null, x]}"

        val result = JsonTokenizer().tokenize(TextSnapshot(text, 3))

        assertEquals(3L, result.version)
        assertEquals(
            listOf(
                TokenKind.PUNCTUATION, TokenKind.STRING, TokenKind.PUNCTUATION, TokenKind.PUNCTUATION, TokenKind.NUMBER,
                TokenKind.PUNCTUATION, TokenKind.KEYWORD, TokenKind.PUNCTUATION, TokenKind.KEYWORD, TokenKind.PUNCTUATION,
                TokenKind.ERROR, TokenKind.PUNCTUATION, TokenKind.PUNCTUATION,
            ),
            result.tokens.map { it.kind },
        )
        assertEquals(1, result.diagnostics.size)
        assertEquals(emptyList(), result.embeddedRegions)
    }
}
