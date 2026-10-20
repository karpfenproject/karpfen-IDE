package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.ui.editor.model.caret.TabStops
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Tab expansion of drawn lines: widths from `TabStops`, columns mapped to offsets of the drawn text. */
class DisplayLineTest {

    @Test
    fun `a line without tabs is drawn as it is`() {
        val line = DisplayLine.of("abc", TabStops(4))

        assertSame("abc", line.text)
        assertEquals(2, line.displayOffsetOf(2))
        assertEquals(3, line.displayOffsetOf(99), "clamped")
        assertEquals(0, line.displayOffsetOf(-1))
    }

    @Test
    fun `tabs advance to the next stop`() {
        val line = DisplayLine.of("\tab\tc", TabStops(4))

        assertEquals("    ab  c", line.text)
        assertEquals(0, line.displayOffsetOf(0))
        assertEquals(4, line.displayOffsetOf(1), "after the first tab")
        assertEquals(6, line.displayOffsetOf(3), "the second tab starts at display column 6")
        assertEquals(8, line.displayOffsetOf(4))
        assertEquals(9, line.displayOffsetOf(5))
    }

    @Test
    fun `the tab width of the settings is used`() {
        assertEquals("a b", DisplayLine.of("a\tb", TabStops(2)).text)
        assertEquals("a       b", DisplayLine.of("a\tb", TabStops(8)).text)
        assertEquals("\t".length * 4, DisplayLine.of("\t", TabStops(4)).text.length)
    }

    @Test
    fun `a surrogate pair is one display column before a tab`() {
        val line = DisplayLine.of("😀\tx", TabStops(4))

        assertEquals("😀   x", line.text, "the pair takes one column, the tab the remaining three")
        assertEquals(5, line.displayOffsetOf(3))
    }
}
