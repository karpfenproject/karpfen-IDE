package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves that the fully-qualified-reference scanner used by the layer rules finds real uses and ignores the rest. */
class SourceReferencesTest {

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
    fun `member access on local names is reported as written`() {
        assertEquals(listOf("value.length"), namesIn("val n = value.length"))
        assertEquals(emptyList(), namesIn("val r = a..b"))
    }
}
