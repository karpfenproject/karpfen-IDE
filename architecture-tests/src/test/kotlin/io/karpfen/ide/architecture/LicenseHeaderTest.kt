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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [LicenseHeader] and how the documentation style rules treat it: the header is required at the top of a file,
 * unchanged and once, and there it is neither a forbidden block comment nor misplaced KDoc, while a copy of it anywhere
 * else is KDoc like any other.
 */
class LicenseHeaderTest {

    /** The header, as every guarded file starts with it. */
    private val header = LicenseHeader.text

    /** The line on which a second header starts that directly follows the first. */
    private val lineAfterHeader = header.lines().size + 1

    /** The lines and reasons of the misplaced KDoc in the Kotlin file [source]. */
    private fun misplaced(source: String): List<Pair<Int, String>> =
        KDocPlacement.check(source, isScript = false).map { it.comment.line to it.reason }

    /** [source] with Windows line breaks, as a checkout that converts line breaks has it. */
    private fun withWindowsLineBreaks(source: String): String = source.replace("\n", "\r\n")

    @Test
    fun `the header followed by file annotations, a package or a script's first statement satisfies the rule`() {
        listOf(
            "$header\npackage a.b\n",
            "$header\n@file:OptIn(ExperimentalApi::class)\n\npackage a.b\n",
            "$header\n/** The plugins. */\nplugins {\n    base\n}\n",
            "$header\nimport a.b.C\n\nplugins {\n}\n",
            withWindowsLineBreaks("$header\npackage a.b\n"),
        ).forEach { source -> assertNull(LicenseHeader.violationIn(source), source) }
    }

    @Test
    fun `a missing, changed, indented, late or merged header breaks the rule`() {
        val missing = "does not start with the license header"
        listOf(
            "package a.b\n",
            header.replace("2026", "2025") + "\npackage a.b\n",
            "/*" + header.removePrefix("/**") + "\npackage a.b\n",
            "\n$header\npackage a.b\n",
            " $header\npackage a.b\n",
            "package a.b\n$header\n",
            "$header package a.b\n",
        ).forEach { source -> assertEquals(missing, LicenseHeader.violationIn(source), source) }
    }

    @Test
    fun `a repeated header breaks the rule`() {
        assertEquals("repeats the license header", LicenseHeader.violationIn("$header\n$header\npackage a.b\n"))
        assertEquals("repeats the license header", LicenseHeader.violationIn("$header\npackage a.b\n\n$header\nclass A\n"))
    }

    @Test
    fun `the header is KDoc in form, and a plain block comment with its text is not`() {
        assertEquals(listOf(CommentKind.KDOC), KotlinComments.scan("$header\npackage a.b\n").map { it.kind })
        assertEquals(listOf(CommentKind.BLOCK), KotlinComments.scan("/*" + header.removePrefix("/**") + "\npackage a.b\n").map { it.kind })
    }

    @Test
    fun `KDoc placement accepts the header at the top of a Kotlin file and of a script`() {
        assertEquals(emptyList(), KDocPlacement.check("$header\npackage a.b\n\n/** A class. */\nclass A\n", isScript = false))
        assertEquals(emptyList(), KDocPlacement.check("$header\n@file:OptIn(X::class)\n\npackage a.b\n", isScript = false))
        assertEquals(emptyList(), KDocPlacement.check("$header\n/** The plugins. */\nplugins {\n}\n", isScript = true))
        assertEquals(emptyList(), KDocPlacement.check(withWindowsLineBreaks("$header\npackage a.b\n"), isScript = false))
    }

    @Test
    fun `a header that is not the first element of the file is KDoc like any other`() {
        val nothing = "documents nothing: another comment or the end follows"

        assertEquals(listOf(lineAfterHeader to "is not followed by a declaration"), misplaced("$header\n$header\npackage a.b\n"))
        assertEquals(listOf(2 to "is not followed by a declaration"), misplaced("\n$header\npackage a.b\n"))
        assertEquals(listOf(1 to "is not followed by a declaration"), misplaced(header.replace("2026", "2025") + "\npackage a.b\n"))
        assertEquals(listOf(lineAfterHeader to nothing), misplaced("$header\n$header\n"))
    }
}
