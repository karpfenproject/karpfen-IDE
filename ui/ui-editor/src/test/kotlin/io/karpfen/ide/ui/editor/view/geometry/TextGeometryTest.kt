package io.karpfen.ide.ui.editor.view.geometry

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.TabStops
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Caret x, hit-testing and range segments in monospace cells with tab stops (decision 40 of the work-package plan). */
class TextGeometryTest {

    /** Cells of 10 px, lines of 20 px, text starting at x = 5. */
    private fun geometry(text: String, tabWidth: Int = 4) = TextGeometry(TextSnapshot(text, 0), TabStops(tabWidth), 10f, 20f, 5f)

    @Test
    fun `the caret x counts display columns`() {
        val geometry = geometry("ab\n\tc")

        assertEquals(5f, geometry.xOf(0))
        assertEquals(25f, geometry.xOf(2))
        assertEquals(5f, geometry.xOf(3), "start of the second line")
        assertEquals(45f, geometry.xOf(4), "after a tab: four cells")
        assertEquals(55f, geometry.xOf(5))
        assertEquals(55f, geometry.xOf(99), "clamped to the text")
        assertEquals(1, geometry.lineOf(4))
        assertEquals(20f, geometry.lineTop(1))
    }

    @Test
    fun `a click hits the nearest cell boundary`() {
        val geometry = geometry("abcd\n\tx\nlast")

        assertEquals(1, geometry.offsetAt(5f + 10f + 4f, 5f), "left half of 'b' places the caret before it")
        assertEquals(2, geometry.offsetAt(5f + 10f + 6f, 5f), "right half after it")
        assertEquals(4, geometry.offsetAt(500f, 5f), "beyond the line end: its end")
        assertEquals(0, geometry.offsetAt(-50f, 5f))
        assertEquals(5, geometry.offsetAt(5f + 15f, 25f), "inside a tab: its nearer bound")
        assertEquals(6, geometry.offsetAt(5f + 25f, 25f))
        assertEquals(0, geometry.offsetAt(5f, -40f), "above the text: the first line")
        assertEquals(geometry.snapshot.length, geometry.offsetAt(500f, 900f), "below the text: the last line")
    }

    @Test
    fun `hovering finds the character under the pointer or nothing`() {
        val geometry = geometry("abcd\n\tx")

        assertEquals(1, geometry.characterAt(5f + 19f, 5f), "the whole cell of 'b'")
        assertEquals(5, geometry.characterAt(5f + 35f, 25f), "the tab spans four cells")
        assertEquals(6, geometry.characterAt(5f + 41f, 25f))
        assertNull(geometry.characterAt(5f + 41f, 5f), "right of the line end")
        assertNull(geometry.characterAt(2f, 5f), "left of the text")
        assertNull(geometry.characterAt(10f, 41f), "below the last line")
        assertNull(geometry.characterAt(10f, -1f))
    }

    @Test
    fun `a range spanning lines is split into segments of the laid-out lines`() {
        val geometry = geometry("abc\nde\nfgh")

        assertEquals(
            listOf(TextGeometry.Segment(0, 15f, 45f), TextGeometry.Segment(1, 5f, 35f), TextGeometry.Segment(2, 5f, 15f)),
            geometry.segments(TextRange(1, 8), 0..2),
            "the line breaks of inner lines are one cell wide",
        )
        assertEquals(listOf(TextGeometry.Segment(1, 5f, 35f)), geometry.segments(TextRange(1, 8), 1..1))
        assertEquals(emptyList(), geometry.segments(TextRange(2, 2), 0..2))
        assertEquals(emptyList(), geometry.segments(TextRange(1, 2), 1..2), "not on the laid-out lines")
    }

    @Test
    fun `the widest line in display columns sizes the content`() {
        assertEquals(6, geometry("ab\n\tcd\nxyz").maxDisplayColumns())
        assertEquals(0, geometry("").maxDisplayColumns())
    }
}
