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
import io.karpfen.ide.architecture.CommentKind.KDOC
import io.karpfen.ide.architecture.CommentKind.LINE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [KotlinComments], the comment scanner behind the documentation style rules, with the inputs that a scanner
 * without a lexer gets wrong: comment delimiters and URLs inside strings, raw strings, character literals, backtick
 * names, string templates with and without a multi-dollar prefix, KDoc, nested block comments and unterminated
 * literals.
 *
 * The inputs are regular string literals, so a raw-string delimiter is written as the template [RAW] and a dollar
 * sign as an escape. This file is itself checked by the documentation style rules, which makes it a test of the
 * scanner as well.
 */
class KotlinCommentsTest {

    private companion object {
        /** The delimiter of a raw string, three quotes. */
        const val RAW = "\"\"\""
    }

    /** The kinds and texts of the comments in [source]. */
    private fun commentsIn(source: String): List<Pair<CommentKind, String>> = KotlinComments.scan(source).map { it.kind to it.text }

    @Test
    fun `finds line comments, block comments and KDoc with the lines they start on`() {
        val source = "/** Doc. */\nval a = 1 // trailing\n/* block */ val b = 2\n\n    // alone\nval c = 3"
        assertEquals(
            listOf(
                SourceComment(KDOC, 1, "/** Doc. */"),
                SourceComment(LINE, 2, "// trailing"),
                SourceComment(BLOCK, 3, "/* block */"),
                SourceComment(LINE, 5, "// alone"),
            ),
            KotlinComments.scan(source),
        )
    }

    @Test
    fun `a URL in KDoc or in a string is no comment`() {
        val source = "/** See https://kotlinlang.org/docs/kotlin-doc.html. */\nval url = \"https://example.org/a//b\""
        assertEquals(listOf(KDOC to "/** See https://kotlinlang.org/docs/kotlin-doc.html. */"), commentsIn(source))
    }

    @Test
    fun `comment delimiters inside string literals are text`() {
        val source = "val a = \"/* not a comment */\" + \"// nor this\" + \"an escaped \\\" quote // still text\""
        assertEquals(emptyList(), commentsIn(source))
    }

    @Test
    fun `raw strings span lines, have no escapes and keep quotes before their closing delimiter`() {
        val source = listOf(
            "val a = $RAW",
            "// text /* text */",
            "say \"hi\" // text",
            "ends with a backslash \\$RAW // first",
            "val b = ${RAW}x\"$RAW // second",
            "val c = ${RAW}y\"\"$RAW + \"//\" // third",
        ).joinToString("\n")
        assertEquals(
            listOf(SourceComment(LINE, 4, "// first"), SourceComment(LINE, 5, "// second"), SourceComment(LINE, 6, "// third")),
            KotlinComments.scan(source),
        )
    }

    @Test
    fun `character literals and backtick names hide quotes and comment delimiters`() {
        assertEquals(listOf(LINE to "// after"), commentsIn("val q = '\"' // after"))
        assertEquals(listOf(BLOCK to "/*c*/"), commentsIn("val slash = '/'/*c*/"))
        assertEquals(emptyList(), commentsIn("val c = '\\'' + \"//\" + '\\\\' + \"/*\""))
        assertEquals(emptyList(), commentsIn("fun `a // b /* c`() = Unit"))
    }

    @Test
    fun `template expressions are code with nested strings, lambdas and comments of their own`() {
        assertEquals(listOf(LINE to "// after"), commentsIn("val a = \"\${ list.map { \"}\" + it } }\" // after"))
        assertEquals(emptyList(), commentsIn("val b = \"\${ \"/* no */\" + \"//\" }\""))
        assertEquals(emptyList(), commentsIn("val c = $RAW\${ \"\\\"\\\"\\\"\" } // text$RAW"))
        assertEquals(listOf(BLOCK to "/* c */"), commentsIn("val d = \"\${ x /* c */ }\""))
        assertEquals(listOf(SourceComment(LINE, 2, "// c")), KotlinComments.scan("val e = $RAW\${\n  x // c\n}$RAW"))
    }

    @Test
    fun `escaped and simple templates open no code`() {
        assertEquals(emptyList(), commentsIn("val a = \"\\\${ // text\""))
        assertEquals(emptyList(), commentsIn("val b = $RAW\${'$'}{ // text$RAW"))
        assertEquals(emptyList(), commentsIn("val c = \"\$name// text\""))
    }

    @Test
    fun `in multi-dollar strings a template opens only on the prefix's number of dollar signs`() {
        assertEquals(emptyList(), commentsIn("val a = \$\$\"literal \${ // not a comment\""))
        assertEquals(emptyList(), commentsIn("val b = \$\$$RAW{ \"pattern\": \"\${ */ /* x\" }$RAW"))
        assertEquals(listOf(BLOCK to "/* c */"), commentsIn("val c = \$\$\"\$\${ x /* c */ }\""))
        assertEquals(listOf(BLOCK to "/* d */"), commentsIn("val d = \$\$$RAW\$\${ x /* d */ }$RAW"))
        assertEquals(
            listOf(SourceComment(LINE, 2, "// a real line comment")),
            KotlinComments.scan("val e = \$\$$RAW\${$RAW\n// a real line comment\nval f = ${RAW}x$RAW"),
        )
    }

    @Test
    fun `block comments nest, KDoc included, and the empty block comment is no KDoc`() {
        assertEquals(
            listOf(BLOCK to "/* outer /* inner */ still outer */", LINE to "// end"),
            commentsIn("/* outer /* inner */ still outer */ val x = 1 // end"),
        )
        assertEquals(listOf(KDOC to "/** Matches src/**/*.kt files. */"), commentsIn("/** Matches src/**/*.kt files. */ val y = 2"))
        assertEquals(listOf(BLOCK to "/**/"), commentsIn("/**/ val z = 3"))
    }

    @Test
    fun `an unterminated literal or comment ends with the line or the text`() {
        assertEquals(listOf(SourceComment(LINE, 2, "// next")), KotlinComments.scan("val a = \"open\n// next"))
        assertEquals(listOf(BLOCK to "/* never closed"), commentsIn("val b = 1 /* never closed"))
        assertEquals(emptyList(), commentsIn("val c = 'x"))
        assertEquals(emptyList(), commentsIn("val d = ${RAW}never closed // text"))
    }

    @Test
    fun `a position inside a comment maps to its line`() {
        val comment = SourceComment(KDOC, 10, "/**\n * First.\n * Second.\n */")
        assertEquals(12, comment.lineAt(comment.text.indexOf("Second")))
        assertEquals("/**", comment.excerpt())
    }
}
