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

/**
 * Covers [SourceReferences], the fully-qualified-reference scanner used by the layer rules: it finds real uses in code,
 * template expressions included, and ignores directives, comments, literals and backtick identifiers.
 */
class SourceReferencesTest {

    /** The names of the qualified references in [source]. */
    private fun namesIn(source: String): List<String> = SourceReferences.qualifiedReferences(source).map { it.name }

    @Test
    fun `finds fully qualified references in code`() {
        val source = """
            package io.karpfen.ide.core.text

            internal val frame: javax.swing.JFrame? = null
            internal fun beep() = java.awt.Toolkit.getDefaultToolkit().beep()
        """.trimIndent()
        assertEquals(
            listOf(
                QualifiedReference("javax.swing.JFrame", 3),
                QualifiedReference("java.awt.Toolkit.getDefaultToolkit", 4),
            ),
            SourceReferences.qualifiedReferences(source),
        )
    }

    @Test
    fun `ignores directives, comments, literals and backtick identifiers`() {
        val source = """
            package io.karpfen.ide.core.text

            import java.awt.Color

            /** See [javax.swing.JFrame]. /* nested java.awt.Frame */ still a comment: javafx.scene.Node */
            // java.awt.Color in a line comment
            internal val text = "javax.swing.JFrame" + ""${'"'}java.awt.Color""${'"'} + 'j'
            internal fun `java.awt is mentioned in a test name`() = Unit
        """.trimIndent()
        assertEquals(emptyList(), namesIn(source))
    }

    @Test
    fun `references in template expressions and after them are code`() {
        assertEquals(listOf("java.awt.Toolkit.getDefaultToolkit"), namesIn("val size = \"\${java.awt.Toolkit.getDefaultToolkit().screenSize}\""))
        assertEquals(listOf("java.awt.Toolkit.getDefaultToolkit"), namesIn("val s = \"\${'\"'}\" + java.awt.Toolkit.getDefaultToolkit()"))
        assertEquals(listOf("java.awt.Toolkit.getDefaultToolkit"), namesIn("val s = \"\${x /* c */ + java.awt.Toolkit.getDefaultToolkit()}\""))
    }

    @Test
    fun `member access on local names is reported as written`() {
        assertEquals(listOf("value.length"), namesIn("val n = value.length"))
        assertEquals(emptyList(), namesIn("val r = a..b"))
    }
}
