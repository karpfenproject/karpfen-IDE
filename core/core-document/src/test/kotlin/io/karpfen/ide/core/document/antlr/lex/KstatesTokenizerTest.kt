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
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [KstatesTokenizer]: the KStates vocabulary, `EVENT` as an operation or a keyword by its block, EVAL bodies
 * piece by piece with the `//` warning, the embedded regions, and symbols, numbers and strings outside EVAL bodies.
 */
class KstatesTokenizerTest {

    private fun tokenize(text: String): TokenizeResult = tokenizeChecked(KarpfenLanguage.KSTATES, text, "KStates '$text'")

    private fun kinds(text: String): List<Pair<TokenKind, String>> =
        tokenize(text).tokens.map { it.kind to text.substring(it.range.start, it.range.end) }

    /** The kinds of the tokens whose text is [word], in document order. */
    private fun kindsOf(text: String, word: String): List<TokenKind> = kinds(text).filter { it.second == word }.map { it.first }

    private val machine = """
        |// A small machine.
        |STATEMACHINE ATTACHED TO "Robot" {
        |    STATES {
        |        INITIAL STATE "idle" {
        |            ENTRY {
        |                SET("speed", "0")
        |                append('log', "entered")
        |                EVENT("public", "started")
        |            }
        |        }
        |    }
        |    TRANSITIONS {
        |        TRANSITION "idle" -> "idle" NOT LOOPING {
        |            CONDITION {
        |                EVENT("public", "tick")
        |                VALUE("ready")
        |            }
        |        }
        |    }
        |    MACROS {
        |        MACRO "closest" {
        |            TAKES("c", list("Obstacle"))
        |            RETURNS(reference("Obstacle"))
        |            DEFINITION { EVAL { return 1 } }
        |        }
        |    }
        |}
    """.trimMargin()

    @Test
    fun `classifies keywords, operations, strings, identifiers and punctuation`() {
        val tokens = kinds(machine)

        assertEquals(TokenKind.COMMENT to "// A small machine.", tokens.first())
        assertEquals(
            listOf(
                TokenKind.KEYWORD to "STATEMACHINE", TokenKind.KEYWORD to "ATTACHED", TokenKind.KEYWORD to "TO",
                TokenKind.STRING to "\"Robot\"", TokenKind.PUNCTUATION to "{",
            ),
            tokens.subList(1, 6),
        )
        assertEquals(listOf(TokenKind.OPERATION), kindsOf(machine, "SET"))
        assertEquals(listOf(TokenKind.OPERATION), kindsOf(machine, "append"))
        assertEquals(listOf(TokenKind.STRING), kindsOf(machine, "'log'"))
        assertEquals(listOf(TokenKind.OPERATOR), kindsOf(machine, "->"))
        assertEquals(listOf(TokenKind.IDENTIFIER), kindsOf(machine, "list"))
        listOf("INITIAL", "STATE", "ENTRY", "TRANSITION", "NOT", "LOOPING", "CONDITION", "VALUE", "MACROS", "MACRO",
            "TAKES", "RETURNS", "reference", "DEFINITION", "EVAL").forEach { keyword ->
            assertEquals(listOf(TokenKind.KEYWORD), kindsOf(machine, keyword), keyword)
        }
        assertTrue(tokenize(machine).diagnostics.isEmpty())
    }

    @Test
    fun `every operation is an OPERATION in both cases`() {
        val operations = listOf("SET", "APPEND", "SETLIST", "DROPLIST", "SETOBJ", "APPENDOBJ", "DROPOBJ")
        val text = (operations + operations.map { it.lowercase() }).joinToString(" ")

        assertEquals(List(operations.size * 2) { TokenKind.OPERATION }, tokenize(text).tokens.map { it.kind })
    }

    @Test
    fun `EVENT is an operation in action blocks and a keyword in conditions`() {
        assertEquals(listOf(TokenKind.OPERATION, TokenKind.KEYWORD), kindsOf(machine, "EVENT"))
    }

    @Test
    fun `EVENT inside IF IN SCOPE and WITH blocks and after EVAL bodies with braces is an operation`() {
        val text = """
            |DO {
            |    SET("d", EVAL { return {1: 2} })
            |    EVENT("a", "b")
            |    IF IN SCOPE("o") { EVENT("a", "b") }
            |    WITH MACRO("dist", "p", "q") AS "d" { EVENT("a", "b") }
            |    EVENT("a", "b")
            |}
            |CONDITION { EVAL { x = {} } EVENT("a", "b") }
            |EVENT("a", "b")
        """.trimMargin()

        assertEquals(
            listOf(TokenKind.OPERATION, TokenKind.OPERATION, TokenKind.OPERATION, TokenKind.OPERATION, TokenKind.KEYWORD, TokenKind.KEYWORD),
            kindsOf(text, "EVENT"),
        )
    }

    @Test
    fun `EVAL bodies are classified piece by piece`() {
        val text = "SET(\"x\", EVAL {\n    # c\n    if \$(a->b) > 1.5: return @dist('s', 2)\n})"

        assertEquals(
            listOf(
                TokenKind.OPERATION to "SET", TokenKind.PUNCTUATION to "(", TokenKind.STRING to "\"x\"",
                TokenKind.PUNCTUATION to ",", TokenKind.KEYWORD to "EVAL", TokenKind.PUNCTUATION to "{",
                TokenKind.EMBEDDED_COMMENT to "# c",
                TokenKind.EMBEDDED_KEYWORD to "if", TokenKind.PATH_REFERENCE to "\$(a->b)", TokenKind.EMBEDDED_CODE to ">",
                TokenKind.EMBEDDED_NUMBER to "1.5", TokenKind.EMBEDDED_CODE to ":", TokenKind.EMBEDDED_KEYWORD to "return",
                TokenKind.MACRO_REFERENCE to "@dist", TokenKind.EMBEDDED_CODE to "(", TokenKind.EMBEDDED_STRING to "'s'",
                TokenKind.EMBEDDED_CODE to ",", TokenKind.EMBEDDED_NUMBER to "2", TokenKind.EMBEDDED_CODE to ")",
                TokenKind.PUNCTUATION to "}", TokenKind.PUNCTUATION to ")",
            ),
            kinds(text),
        )
    }

    @Test
    fun `a double slash inside EVAL is a karpfen comment with a warning`() {
        val text = "SET(\"x\", EVAL {\n    return a // 2\n})"

        val result = tokenize(text)

        assertTrue(TokenKind.COMMENT to "// 2" in kinds(text))
        val warning = result.diagnostics.single()
        assertEquals(Severity.WARNING, warning.severity)
        assertEquals(DiagnosticCodes.EVAL_DOUBLE_SLASH, warning.code)
        assertEquals(DiagnosticSource.IDE, warning.source)
        assertEquals("// 2", text.substring(warning.range.start, warning.range.end))
        assertEquals(
            "`//` inside EVAL starts a karpfen comment; the rest of the line is dropped. " +
                "For Python floor division use int(a / b) or math.floor(a / b).",
            warning.message,
        )
    }

    @Test
    fun `a double slash outside EVAL is an ordinary comment without a warning`() {
        assertTrue(tokenize("STATES { // c\n}").diagnostics.isEmpty())
    }

    @Test
    fun `embedded regions are the content of the EVAL bodies`() {
        val text = "EVAL { a } EVAL {} EVAL { b = { } }"

        val result = tokenize(text)

        assertEquals(listOf(TextRange(6, 9), TextRange(17, 17), TextRange(25, 34)), result.embeddedRegions)
        assertEquals(TextRange(6, 9), result.embeddedRegionAt(6))
        assertEquals(TextRange(25, 34), result.embeddedRegionAt(30))
        assertNull(result.embeddedRegionAt(12))
    }

    @Test
    fun `an unclosed EVAL body runs to the end of the text`() {
        val text = "SET(\"x\", EVAL { return \$(a"

        val result = tokenize(text)

        assertEquals(listOf(TextRange(15, text.length)), result.embeddedRegions)
        assertEquals(TokenKind.PATH_REFERENCE to "\$(a", kinds(text).last())
    }

    @Test
    fun `symbols outside EVAL bodies are error tokens without diagnostics of their own`() {
        val text = "STATES { # \$ @ 😀 }"

        val result = tokenize(text)

        assertEquals(
            listOf(
                TokenKind.KEYWORD to "STATES", TokenKind.PUNCTUATION to "{", TokenKind.ERROR to "#", TokenKind.ERROR to "\$",
                TokenKind.ERROR to "@", TokenKind.ERROR to "😀", TokenKind.PUNCTUATION to "}",
            ),
            kinds(text),
        )
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `numbers and unterminated strings outside EVAL keep every character in a token`() {
        val text = "VALUE(12.5) \"open"

        assertEquals(
            listOf(
                TokenKind.KEYWORD to "VALUE", TokenKind.PUNCTUATION to "(", TokenKind.NUMBER to "12.5",
                TokenKind.PUNCTUATION to ")", TokenKind.ERROR to "\"", TokenKind.IDENTIFIER to "open",
            ),
            kinds(text),
        )
    }
}
