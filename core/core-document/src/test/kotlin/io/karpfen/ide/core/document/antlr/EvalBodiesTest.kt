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
package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

/**
 * Covers [EvalBodies], the one lexical scanner of embedded Python: finding bodies with braces balanced like the
 * dsl-tools parser, unclosed bodies, the classification of pieces, and agreement between the tokenizer's and the
 * parser's token streams.
 */
class EvalBodiesTest {

    private fun scanAll(text: String): List<Pair<EvalBody, List<EmbeddedPiece>>> {
        val lexed = KarpfenLexers.lex(DslGrammar.KSTATES, text)
        return EvalBodies.scanAll(text, lexed.tokens, lexed.offsets)
    }

    private fun String.at(range: TextRange): String = substring(range.start, range.end)

    private fun pieces(text: String): List<Pair<EmbeddedKind, String>> =
        scanAll(text).single().second.map { it.kind to text.at(it.range) }

    @Test
    fun `finds the body between EVAL's brace and the matching one`() {
        val text = "SET(\"x\", EVAL { return \$(a->b) + 1 })"

        val body = scanAll(text).single().first

        assertEquals("EVAL", text.at(body.keyword))
        assertEquals(" return \$(a->b) + 1 ", text.at(body.content))
        assertEquals("}", text.at(body.close!!))
    }

    @Test
    fun `balances braces on lexer tokens exactly as the dsl-tools parser does`() {
        val text = "EVAL { d = {1: 2} } EVAL { s = \"}\" + '}' # {\n x }\n } EVAL // c\n { y // }\n }"

        assertEquals(
            listOf(" d = {1: 2} ", " s = \"}\" + '}' # {\n x }\n ", " y // }\n "),
            scanAll(text).map { (body, _) -> text.at(body.content) },
        )
    }

    @Test
    fun `an unclosed body runs to the end and EVAL without a brace has none`() {
        val body = scanAll("EVAL { x = 1").single().first
        assertNull(body.close)
        assertEquals(TextRange(6, 12), body.content)
        assertTrue(scanAll("EVAL x = 1").isEmpty())
    }

    @Test
    fun `classifies paths, macros, keywords, strings and numbers`() {
        val text = "EVAL { return \$(boundingBox->position->x) + 0.3 * \$( dir -> x ) and @dist(\$(a), 'b') }"

        assertEquals(
            listOf(
                EmbeddedKind.KEYWORD to "return",
                EmbeddedKind.PATH_REFERENCE to "\$(boundingBox->position->x)",
                EmbeddedKind.CODE to "+",
                EmbeddedKind.NUMBER to "0.3",
                EmbeddedKind.CODE to "*",
                EmbeddedKind.PATH_REFERENCE to "\$( dir -> x )",
                EmbeddedKind.KEYWORD to "and",
                EmbeddedKind.MACRO_REFERENCE to "@dist",
                EmbeddedKind.CODE to "(",
                EmbeddedKind.PATH_REFERENCE to "\$(a)",
                EmbeddedKind.CODE to ",",
                EmbeddedKind.STRING to "'b'",
                EmbeddedKind.CODE to ")",
            ),
            pieces(text),
        )
        val references = scanAll(text).single().second.filter { it.name != null }
        assertEquals(listOf("boundingBox->position->x", "dir->x", "dist", "a"), references.map { it.name })
        assertEquals("dir -> x", text.at(references[1].inner!!))
        assertEquals("dist", text.at(references[2].inner!!))
    }

    @Test
    fun `hash comments swallow their line, double slashes are karpfen comments`() {
        val text = "EVAL {\n  x = a // 2\n  # note \$(c) 'q' {\n  y = \"#\" }\n}"

        assertEquals(
            listOf(
                EmbeddedKind.CODE to "x",
                EmbeddedKind.CODE to "=",
                EmbeddedKind.CODE to "a",
                EmbeddedKind.KARPFEN_COMMENT to "// 2",
                EmbeddedKind.COMMENT to "# note \$(c) 'q' {",
                EmbeddedKind.CODE to "y",
                EmbeddedKind.CODE to "=",
                EmbeddedKind.STRING to "\"#\"",
                EmbeddedKind.CODE to "}",
            ),
            pieces(text),
        )
    }

    @Test
    fun `unclosed path references end after the path`() {
        val unclosed = scanAll("EVAL { \$(a->b + 1 }").single().second
        assertEquals(EmbeddedPiece(TextRange(7, 13), EmbeddedKind.PATH_REFERENCE, TextRange(9, 13), "a->b", closed = false), unclosed.first())
        assertEquals(listOf(EmbeddedKind.CODE, EmbeddedKind.NUMBER), unclosed.drop(1).map { it.kind })

        val empty = scanAll("EVAL { \$( }").single().second.single()
        assertEquals("", empty.name)
        assertFalse(empty.closed)
        assertEquals(TextRange(7, 9), empty.range)

        val cut = "EVAL { \$(1.5 }"
        assertEquals(listOf(EmbeddedKind.PATH_REFERENCE to "\$(1.5"), pieces(cut), "the cut number is swallowed")
        assertEquals(listOf(EmbeddedKind.CODE, EmbeddedKind.CODE, EmbeddedKind.STRING), scanAll("EVAL { s = \"\$(x)\" }").single().second.map { it.kind })
    }

    @Test
    fun `tokenizer and parser token streams yield the same bodies`() {
        val text = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text
        val lexed = KarpfenLexers.lex(DslGrammar.KSTATES, text)
        val listener = IdeErrorListener(lexed.offsets)
        val stream = CommonTokenStream(KarpfenLexers.create(DslGrammar.KSTATES, CharStreams.fromString(text), listener))
        stream.fill()

        val fromLexer = EvalBodies.find(lexed.tokens, lexed.offsets)
        val fromParserStream = EvalBodies.find(stream.tokens, lexed.offsets)

        assertTrue(fromLexer.size >= 10, "bodies: ${fromLexer.size}")
        assertEquals(fromLexer.map { it.content to it.close }, fromParserStream.map { it.content to it.close })
        assertEquals(
            fromLexer.map { EvalBodies.scan(text, lexed.tokens, lexed.offsets, it) },
            fromParserStream.map { EvalBodies.scan(text, stream.tokens, lexed.offsets, it) },
        )
    }

    @Test
    fun `pieces are sorted, disjoint and cover every non-whitespace character of every body`() {
        val machine = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text
        val texts = Examples.files(KarpfenLanguage.KSTATES).map { it.text } + SyntheticKstates(60) +
            Mutations.random(machine, seed = 17, count = 1500).map { it.text } + Mutations.prefixes(machine, step = 13)

        for (text in texts) {
            for ((body, pieces) in scanAll(text)) {
                val label = "body ${body.content} of ${text.take(60)}"
                assertTrue(body.content.end <= text.length, label)
                for (i in pieces.indices) {
                    val piece = pieces[i]
                    assertTrue(piece.range in body.content, "$piece outside $label")
                    if (i > 0) assertTrue(pieces[i - 1].range.end <= piece.range.start, "overlap at $piece in $label")
                }
                val covered = BooleanArray(text.length)
                pieces.forEach { piece -> for (k in piece.range.start until piece.range.end) covered[k] = true }
                for (k in body.content.start until body.content.end) {
                    assertTrue(covered[k] || text[k] in " \t\r\n", "uncovered '${text[k]}' at $k in $label")
                }
            }
        }
    }
}
