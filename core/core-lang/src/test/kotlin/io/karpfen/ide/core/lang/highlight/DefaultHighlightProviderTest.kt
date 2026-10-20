package io.karpfen.ide.core.lang.highlight

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lexical highlighting overlaid with the semantic spans of a parse of the tokens' version (spec §5, decision 39). */
class DefaultHighlightProviderTest {

    private val provider = DefaultHighlightProvider()

    private fun highlight(name: String, text: String, version: Long = 1) =
        ExampleProject.document(Path.of("/ws/$name"), text, version).let { provider.highlight(ExampleProject.request(it)) }

    @Test
    fun `every token kind and semantic kind maps to the style of the same name`() {
        TokenKind.entries.forEach { assertEquals(it.name, DefaultHighlightProvider.styleOf(it).name) }
        SemanticKind.entries.forEach {
            val style = DefaultHighlightProvider.styleOf(it)
            assertEquals(it.name, style.name)
            assertTrue(style.isSemantic)
        }
    }

    @Test
    fun `semantic spans split the string tokens around names when the parse has the tokens' version`() {
        val text = "type \"Vector\" \"A vector\" { prop(\"x\", \"number\") }"

        val spans = highlight("a.kmeta", text)

        fun styleOf(word: String) = spans.single { it.range == TextRange(text.indexOf(word), text.indexOf(word) + word.length) }.style
        assertEquals(HighlightStyle.KEYWORD, styleOf("type"))
        assertEquals(HighlightStyle.TYPE_NAME, styleOf("Vector"))
        assertEquals(HighlightStyle.DESCRIPTION, styleOf("A vector"))
        assertEquals(HighlightStyle.PROPERTY_NAME, styleOf("x"))
        assertEquals(HighlightStyle.PRIMITIVE_TYPE, styleOf("number"))
        assertEquals(HighlightSpan(TextRange(5, 6), HighlightStyle.STRING), spans[1], "the opening quote stays a string")
    }

    @Test
    fun `an outcome of another version contributes nothing`() {
        val text = "type \"Vector\" \"A vector\" { }"
        val document = ExampleProject.document(Path.of("/ws/a.kmeta"), text, version = 4)
        val stale = document.copy(parse = document.parse?.copy(version = 3), lastGoodParse = null)

        val spans = provider.highlight(ExampleProject.request(stale))

        assertEquals(document.tokens.tokens.map { HighlightSpan(it.range, DefaultHighlightProvider.styleOf(it.kind)) }, spans)
        assertTrue(spans.none { it.style.isSemantic })
        val lastGoodOnly = document.copy(parse = stale.parse, lastGoodParse = document.parse)
        assertTrue(provider.highlight(ExampleProject.request(lastGoodOnly)).any { it.style == HighlightStyle.TYPE_NAME })
    }

    @Test
    fun `embedded Python keeps its lexical styles and paths get the model-path style`() {
        val text = "SET(\"a\", EVAL { return \$(b->c) # note\n })"

        val spans = highlight("a.kstates", text)

        fun styleAt(offset: Int) = spans.single { offset in it.range }.style
        assertEquals(HighlightStyle.EMBEDDED_KEYWORD, styleAt(text.indexOf("return")))
        assertEquals(HighlightStyle.PATH_REFERENCE, styleAt(text.indexOf("$(")))
        assertEquals(HighlightStyle.MODEL_PATH, styleAt(text.indexOf("b->c")))
        assertEquals(HighlightStyle.EMBEDDED_COMMENT, styleAt(text.indexOf("# note")))
    }

    @Test
    fun `spans of every example are sorted, non-overlapping, non-empty and cover every token`() {
        for (example in Examples.files()) {
            val document = ExampleProject.document(Path.of("/ws/${example.fileName}"), example.text)
            val spans = provider.highlight(ExampleProject.request(document))

            spans.zipWithNext().forEach { (a, b) -> assertTrue(a.range.end <= b.range.start, "$example: $a before $b") }
            assertTrue(spans.none { it.range.isEmpty }, "$example")
            val covered = spans.sumOf { it.range.length }
            assertEquals(document.tokens.tokens.sumOf { it.range.length }, covered, "$example: the overlay keeps the token coverage")
            assertTrue(spans.any { it.style.isSemantic }, "$example")
        }
    }
}
