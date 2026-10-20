package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.ui.editor.model.caret.TabStops
import io.karpfen.ide.ui.editor.theme.darkEditorTheme
import io.karpfen.ide.ui.editor.theme.lightEditorTheme
import io.karpfen.ide.ui.editor.view.geometry.DisplayLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The per-line layout LRU cache and the line layouter that styles and measures lines through it. */
class LineLayoutTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)
    private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    private fun key(text: String) = LineLayoutKey(text, 0, 13f, 4)

    @Test
    fun `the cache evicts the least recently used layout`() {
        val cache = LineLayoutCache(2)
        val a = measurer.measure("a", style)
        val b = measurer.measure("b", style)
        val c = measurer.measure("c", style)
        cache.put(key("a"), a)
        cache.put(key("b"), b)

        assertSame(a, cache.get(key("a")), "a is now the most recently used")
        cache.put(key("c"), c)

        assertNull(cache.get(key("b")))
        assertSame(a, cache.get(key("a")))
        assertSame(c, cache.get(key("c")))
        assertEquals(2, cache.size)
    }

    @Test
    fun `lines are styled by their spans at display offsets and measured once`() {
        val cache = LineLayoutCache(8)
        val layouter = LineLayouter(measurer, cache)
        val theme = lightEditorTheme()
        val line = DisplayLine.of("\tSTATE \"a\"", TabStops(4))
        val spans = listOf(LineSpan(1, 6, HighlightStyle.KEYWORD), LineSpan(8, 9, HighlightStyle.STATE_NAME))

        val first = layouter.layout(line, spans, style, theme, 13f, 4)

        val styled = first.layoutInput.text
        assertEquals("    STATE \"a\"", styled.text)
        assertEquals(listOf(4 to 9, 11 to 12), styled.spanStyles.map { it.start to it.end })
        assertEquals(theme.styles.getValue(HighlightStyle.STATE_NAME), styled.spanStyles[1].item)
        assertSame(first, layouter.layout(line, spans, style, theme, 13f, 4), "a cache hit")
        assertNotSame(first, layouter.layout(line, spans.take(1), style, theme, 13f, 4), "other spans are measured anew")
        assertNotSame(first, layouter.layout(line, spans, style, darkEditorTheme(), 13f, 4), "another theme too")
        assertEquals(3, cache.size)
    }
}
