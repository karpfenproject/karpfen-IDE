package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Placement of anchored popups in the overlay area (pure). */
class PopupPlacementTest {

    private val area = IntSize(400, 300)
    private val caret = Rect(40f, 100f, 41f, 116f)

    @Test
    fun `a popup that fits goes below its anchor, starting at the anchor's left edge`() {
        assertEquals(IntOffset(40, 116), PopupPlacement.topLeft(caret, IntSize(120, 150), area))
    }

    @Test
    fun `a popup without room below goes above its anchor`() {
        assertEquals(IntOffset(40, 100 - 90), PopupPlacement.topLeft(caret, IntSize(120, 90), IntSize(400, 150)))
    }

    @Test
    fun `a popup fitting nowhere goes to the side with more room`() {
        val low = Rect(0f, 200f, 1f, 216f)
        assertEquals(IntOffset(0, 0), PopupPlacement.topLeft(low, IntSize(50, 250), area), "more room above: clamped at the top")
        val high = Rect(0f, 50f, 1f, 66f)
        assertEquals(IntOffset(0, 66), PopupPlacement.topLeft(high, IntSize(50, 250), area), "more room below")
    }

    @Test
    fun `the height limit is the larger room`() {
        assertEquals(184, PopupPlacement.maxHeight(caret, area.height))
        assertEquals(200, PopupPlacement.maxHeight(Rect(0f, 200f, 1f, 216f), area.height))
        assertEquals(0, PopupPlacement.maxHeight(Rect(0f, 0f, 1f, 400f), area.height))
    }

    @Test
    fun `a popup is moved left to end inside the area but never beyond its left edge`() {
        val right = Rect(380f, 0f, 381f, 16f)
        assertEquals(IntOffset(400 - 120, 16), PopupPlacement.topLeft(right, IntSize(120, 50), area))
        assertEquals(IntOffset(0, 16), PopupPlacement.topLeft(right, IntSize(500, 50), area), "wider than the area")
    }

    @Test
    fun `a popup within the height limit always lies inside the area vertically`() {
        val random = Random(20261020)
        repeat(1_000) {
            val area = IntSize(random.nextInt(1, 800), random.nextInt(1, 800))
            val left = random.nextInt(0, area.width).toFloat()
            val top = random.nextInt(0, area.height).toFloat()
            val anchor = Rect(left, top, left + 1f, minOf(top + random.nextInt(1, 30), area.height.toFloat()))
            val height = random.nextInt(0, PopupPlacement.maxHeight(anchor, area.height) + 1)
            val width = random.nextInt(0, area.width + 1)

            val at = PopupPlacement.topLeft(anchor, IntSize(width, height), area)

            assertTrue(at.y >= 0 && at.y + height <= area.height, "y $at for $anchor in $area, height $height")
            assertTrue(at.x >= 0 && at.x + width <= area.width, "x $at for $anchor in $area, width $width")
            assertTrue(at.y >= anchor.bottom.toInt() || at.y + height <= anchor.top.toInt(), "never covers the anchor: $at, $anchor")
        }
    }
}
