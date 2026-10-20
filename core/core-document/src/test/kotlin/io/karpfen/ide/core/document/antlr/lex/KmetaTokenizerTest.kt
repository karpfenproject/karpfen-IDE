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
