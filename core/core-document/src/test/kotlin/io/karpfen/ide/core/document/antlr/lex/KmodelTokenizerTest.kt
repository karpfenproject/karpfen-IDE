package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [KmodelTokenizer]: the KModel vocabulary with both string quotes, and unrecognized input as an error token
 * with a lexer diagnostic.
 */
class KmodelTokenizerTest {

    private fun tokenize(text: String): TokenizeResult = tokenizeChecked(KarpfenLanguage.KMODEL, text, "KModel '$text'")

    private fun kinds(text: String): List<Pair<TokenKind, String>> =
        tokenize(text).tokens.map { it.kind to text.substring(it.range.start, it.range.end) }

    @Test
    fun `classifies keywords, operators, punctuation and both string quotes`() {
        val text = "make object \"r\":\"Robot\" {\n  prop(\"speed\") -> \"1\"\n  has(\"p\") -> make object 'p1':'Vector' { }\n" +
            "  knows(\"o\") -> \"x\" // the obstacle\n}"

        assertEquals(
            listOf(
                TokenKind.KEYWORD to "make", TokenKind.KEYWORD to "object", TokenKind.STRING to "\"r\"",
                TokenKind.PUNCTUATION to ":", TokenKind.STRING to "\"Robot\"", TokenKind.PUNCTUATION to "{",
                TokenKind.KEYWORD to "prop", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"speed\"",
                TokenKind.PUNCTUATION to ")", TokenKind.OPERATOR to "->", TokenKind.STRING to "\"1\"",
                TokenKind.KEYWORD to "has", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"p\"",
                TokenKind.PUNCTUATION to ")", TokenKind.OPERATOR to "->", TokenKind.KEYWORD to "make",
                TokenKind.KEYWORD to "object", TokenKind.STRING to "'p1'", TokenKind.PUNCTUATION to ":",
                TokenKind.STRING to "'Vector'", TokenKind.PUNCTUATION to "{", TokenKind.PUNCTUATION to "}",
                TokenKind.KEYWORD to "knows", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"o\"",
                TokenKind.PUNCTUATION to ")", TokenKind.OPERATOR to "->", TokenKind.STRING to "\"x\"",
                TokenKind.COMMENT to "// the obstacle", TokenKind.PUNCTUATION to "}",
            ),
            kinds(text),
        )
        assertTrue(tokenize(text).diagnostics.isEmpty())
    }

    @Test
    fun `unrecognized input is an error token with a lexer diagnostic`() {
        val text = "make object \"r\":\"R\" { prop(\"a\") = \"1\" }"

        val result = tokenize(text)

        assertTrue(TokenKind.ERROR to "=" in kinds(text))
        assertEquals(listOf(DiagnosticSource.LEXER), result.diagnostics.map { it.source })
    }
}
