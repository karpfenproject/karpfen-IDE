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
package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [KmetaTokenizer]: the KMeta vocabulary, recovered comments, unrecognized input and unterminated strings as
 * error tokens with lexer diagnostics, UTF-16 offsets, and the result's version.
 */
class KmetaTokenizerTest {

    private fun tokenize(text: String): TokenizeResult = tokenizeChecked(KarpfenLanguage.KMETA, text, "KMeta '$text'")

    private fun kinds(text: String): List<Pair<TokenKind, String>> =
        tokenize(text).tokens.map { it.kind to text.substring(it.range.start, it.range.end) }

    @Test
    fun `classifies keywords, strings and punctuation`() {
        assertEquals(
            listOf(
                TokenKind.KEYWORD to "type", TokenKind.STRING to "\"Robot\"", TokenKind.STRING to "\"A robot\"",
                TokenKind.PUNCTUATION to "{",
                TokenKind.KEYWORD to "prop", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"speed\"",
                TokenKind.PUNCTUATION to ",", TokenKind.STRING to "\"number\"", TokenKind.PUNCTUATION to ")",
                TokenKind.KEYWORD to "has", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"p\"",
                TokenKind.PUNCTUATION to ",", TokenKind.STRING to "\"Vector\"", TokenKind.PUNCTUATION to ")",
                TokenKind.KEYWORD to "knows", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"o\"",
                TokenKind.PUNCTUATION to ",", TokenKind.KEYWORD to "list", TokenKind.PUNCTUATION to "(",
                TokenKind.STRING to "\"Obstacle\"", TokenKind.PUNCTUATION to ")", TokenKind.PUNCTUATION to ")",
                TokenKind.PUNCTUATION to "}",
            ),
            kinds("type \"Robot\" \"A robot\" {\n  prop(\"speed\", \"number\")\n  has(\"p\", \"Vector\")\n  knows(\"o\", list(\"Obstacle\"))\n}"),
        )
    }

    @Test
    fun `recovers the comments the lexer skips`() {
        val text = "// head\ntype \"A\" \"d\" { // inline }\n}\r\n// tail"

        assertEquals(
            listOf(
                TokenKind.COMMENT to "// head", TokenKind.KEYWORD to "type", TokenKind.STRING to "\"A\"",
                TokenKind.STRING to "\"d\"", TokenKind.PUNCTUATION to "{", TokenKind.COMMENT to "// inline }",
                TokenKind.PUNCTUATION to "}", TokenKind.COMMENT to "// tail",
            ),
            kinds(text),
        )
        assertTrue(tokenize(text).diagnostics.isEmpty())
    }

    @Test
    fun `unrecognized input becomes error tokens with lexer diagnostics`() {
        val text = "type \"A\" \"d\" { @ prop }"

        val result = tokenize(text)

        assertEquals(TokenKind.ERROR to "@", kinds(text)[4])
        val diagnostic = result.diagnostics.single()
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(DiagnosticCodes.LEXER_ERROR, diagnostic.code)
        assertEquals(DiagnosticSource.LEXER, diagnostic.source)
        assertEquals("@", text.substring(diagnostic.range.start, diagnostic.range.end))
    }

    @Test
    fun `an unterminated string is an error token and the next line lexes again`() {
        val text = "type \"A\" \"unterminated\n{ }"

        val result = tokenize(text)

        assertEquals(
            listOf(
                TokenKind.KEYWORD to "type", TokenKind.STRING to "\"A\"", TokenKind.ERROR to "\"unterminated",
                TokenKind.PUNCTUATION to "{", TokenKind.PUNCTUATION to "}",
            ),
            kinds(text),
        )
        assertEquals(listOf(DiagnosticSource.LEXER), result.diagnostics.map { it.source })
    }

    /** The lexer's recovery swallows the first `/` with the failed `type`, so it never lexes a comment there. */
    @Test
    fun `a double slash the lexer consumed while recovering from an error is not a comment`() {
        val text = "ty//pe \"A\" \"d\" { }"

        val tokens = kinds(text)

        assertEquals(listOf(TokenKind.ERROR to "ty", TokenKind.ERROR to "//pe "), tokens.take(2))
        assertEquals(TokenKind.STRING to "\"A\"", tokens[2])
        assertTrue(tokenize(text).diagnostics.all { it.source == DiagnosticSource.LEXER })
    }

    @Test
    fun `a comment after an unterminated string belongs to the error and one on its own line stays a comment`() {
        val text = "type \"A\" \"open // not a comment\n// a comment\n{ }"

        assertEquals(
            listOf(
                TokenKind.KEYWORD to "type", TokenKind.STRING to "\"A\"", TokenKind.ERROR to "\"open",
                TokenKind.ERROR to "// not a comment", TokenKind.COMMENT to "// a comment", TokenKind.PUNCTUATION to "{",
                TokenKind.PUNCTUATION to "}",
            ),
            kinds(text),
        )
    }

    @Test
    fun `offsets are UTF-16 code units also after supplementary characters`() {
        val text = "// 😀 smile\ntype \"😀\" \"d\" { } 😀"

        assertEquals(
            listOf(
                TokenKind.COMMENT to "// 😀 smile", TokenKind.KEYWORD to "type", TokenKind.STRING to "\"😀\"",
                TokenKind.STRING to "\"d\"", TokenKind.PUNCTUATION to "{", TokenKind.PUNCTUATION to "}",
                TokenKind.ERROR to "😀",
            ),
            kinds(text),
        )
    }

    @Test
    fun `the empty text and whitespace have no tokens`() {
        assertEquals(TokenizeResult.empty(7), tokenize(""))
        assertEquals(TokenizeResult.empty(7), tokenize(" \t\r\n "))
    }

    @Test
    fun `the result carries the snapshot's version`() {
        assertEquals(42, KmetaTokenizer().tokenize(TextSnapshot("type", 42)).version)
    }
}
