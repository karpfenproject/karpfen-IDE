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
package io.karpfen.ide.architecture

import io.karpfen.ide.architecture.CommentKind.BLOCK
import io.karpfen.ide.architecture.CommentKind.LINE
import io.karpfen.ide.architecture.LiteralKind.BACKTICK_NAME
import io.karpfen.ide.architecture.LiteralKind.CHARACTER
import io.karpfen.ide.architecture.LiteralKind.STRING
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [KotlinLexer], the one lexer of the architecture tests: the spans it finds, the code that remains when they
 * are blanked, and the template expressions of strings with and without a multi-dollar prefix. [KotlinCommentsTest]
 * covers the comments it finds.
 *
 * The inputs are regular string literals, so a raw-string delimiter is written as the template [RAW] and a dollar sign
 * as an escape.
 */
class KotlinLexerTest {

    private companion object {
        /** The delimiter of a raw string, three quotes. */
        const val RAW = "\"\"\""
    }

    /** The kinds and texts of the spans of [source]. */
    private fun spansIn(source: String): List<Pair<SpanKind, String>> =
        KotlinLexer.spans(source).map { it.kind to source.substring(it.start, it.end) }

    /** The words of the code of [source], the text between blanks once comments and literal text are blanked. */
    private fun codeWordsIn(source: String): List<String> = KotlinLexer.code(source).split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `finds comments, string pieces, character literals and backtick names`() {
        assertEquals(
            listOf(LINE to "// line", STRING to "\"text\"", CHARACTER to "'c'", BACKTICK_NAME to "`a name`", BLOCK to "/* block */"),
            spansIn("// line\nval s = \"text\" + 'c'\nfun `a name`() = 1 /* block */"),
        )
    }

    @Test
    fun `a string with template expressions is split into pieces around the code of the expressions`() {
        assertEquals(listOf(STRING to "\"a\${", STRING to "}b\${", STRING to "}\""), spansIn("val s = \"a\${x}b\${ f { y } }\""))
        assertEquals(listOf("val", "s", "=", "x", "f", "{", "y", "}"), codeWordsIn("val s = \"a\${x}b\${ f { y } }\""))
    }

    @Test
    fun `the code keeps the offsets and line breaks of the source`() {
        val source = "/* a\nb */ val x = \"\${java.awt.Color.RED}\" + '\"' + `c d`()\n// end"
        val code = KotlinLexer.code(source)

        assertEquals(source.length, code.length)
        assertEquals(source.indices.filter { source[it] == '\n' }, code.indices.filter { code[it] == '\n' })
        assertEquals(source.indexOf("java.awt"), code.indexOf("java.awt"))
        assertEquals(listOf("val", "x", "=", "java.awt.Color.RED", "+", "+", "()"), codeWordsIn(source))
    }

    @Test
    fun `the code outside strings blanks whole string literals, their template expressions included`() {
        val source = "assertEquals(1, result, \"after \${mark.elapsedNow()} and \${f(\"x\")}\") // \${y}"
        val code = KotlinLexer.codeOutsideStrings(source)

        assertEquals(source.length, code.length)
        assertEquals(listOf("assertEquals(1,", "result,", ")"), code.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }

    @Test
    fun `a multi-dollar string opens a template only on as many dollar signs as its prefix has`() {
        assertEquals(listOf(STRING to "\$\$\"literal \${ x }\""), spansIn("\$\$\"literal \${ x }\""))
        assertEquals(listOf(STRING to "\$\$\"a \$\${", STRING to "} b\""), spansIn("\$\$\"a \$\${ x } b\""))
        assertEquals(listOf("val", "c", "=", "x"), codeWordsIn("val c = \$\$\"\$\$\$\${ x }\""))
        assertEquals(listOf("val", "d", "="), codeWordsIn("val d = \$\$\"\\\$\${ x }\""))
    }

    @Test
    fun `a multi-dollar raw string opens a template only on as many dollar signs as its prefix has`() {
        assertEquals(listOf(STRING to "\$\$$RAW{ \"p\": \"\${ x\" }$RAW"), spansIn("\$\$$RAW{ \"p\": \"\${ x\" }$RAW"))
        assertEquals(listOf("val", "e", "=", "y"), codeWordsIn("val e = \$\$\$$RAW\$\$\${ y }\$\${ text }$RAW"))
    }

    @Test
    fun `a dollar sign before code or a character literal opens no string`() {
        assertEquals(listOf(CHARACTER to "'\$'", LINE to "// c"), spansIn("val f = '\$' // c"))
        assertEquals(listOf(BACKTICK_NAME to "`a\$b`"), spansIn("val `a\$b` = 1"))
    }
}
