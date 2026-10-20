package io.karpfen.ide.ui.foundation.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SplitGeometryTest {

    private val first = SplitGeometry(SplitSide.FIRST, minFirst = 80f, minSecond = 100f, divider = 1f)
    private val second = SplitGeometry(SplitSide.SECOND, minFirst = 80f, minSecond = 100f, divider = 1f)

    @Test
    fun `the fixed pane keeps its size and the other pane takes the rest`() {
        assertEquals(SplitSizes(200f, 399f), first.layout(container = 600f, fixed = 200f))
        assertEquals(SplitSizes(399f, 200f), second.layout(container = 600f, fixed = 200f))
        assertEquals(SplitSizes(200f, 599f), first.layout(container = 800f, fixed = 200f))
    }

    @Test
    fun `the fixed pane never goes below its minimum`() {
        assertEquals(SplitSizes(80f, 519f), first.layout(600f, fixed = 10f))
        assertEquals(SplitSizes(499f, 100f), second.layout(600f, fixed = 10f))
    }

    @Test
    fun `the fixed pane shrinks so the other pane keeps its minimum`() {
        assertEquals(SplitSizes(499f, 100f), first.layout(600f, fixed = 590f))
        assertEquals(SplitSizes(80f, 519f), second.layout(600f, fixed = 590f))
    }

    @Test
    fun `a container smaller than both minimums keeps the fixed minimum and shrinks the other pane`() {
        assertEquals(SplitSizes(80f, 69f), first.layout(150f, fixed = 200f))
        assertEquals(SplitSizes(49f, 100f), second.layout(150f, fixed = 200f))
    }

    @Test
    fun `a container smaller than the fixed minimum gives it all the space`() {
        assertEquals(SplitSizes(49f, 0f), first.layout(50f, fixed = 200f))
        assertEquals(SplitSizes(0f, 0f), first.layout(0f, fixed = 200f))
    }

    @Test
    fun `dragging moves the divider and grows or shrinks the fixed pane depending on its side`() {
        assertEquals(250f, first.dragged(600f, start = 200f, delta = 50f))
        assertEquals(150f, first.dragged(600f, start = 200f, delta = -50f))
        assertEquals(150f, second.dragged(600f, start = 200f, delta = 50f))
        assertEquals(250f, second.dragged(600f, start = 200f, delta = -50f))
    }

    @Test
    fun `dragging is clamped to both minimums`() {
        assertEquals(80f, first.dragged(600f, start = 200f, delta = -500f))
        assertEquals(499f, first.dragged(600f, start = 200f, delta = 500f))
        assertEquals(100f, second.dragged(600f, start = 200f, delta = 500f))
        assertEquals(519f, second.dragged(600f, start = 200f, delta = -500f))
    }

    @Test
    fun `an unbounded container holds the fixed pane, the divider and the other pane's minimum`() {
        assertEquals(301f, first.naturalContainer(200f))
        assertEquals(181f, first.naturalContainer(10f))
        assertEquals(281f, second.naturalContainer(200f))
    }

    @Test
    fun `the drag tracker follows the pointer from the press and reports only changes`() {
        val tracker = SplitDragTracker()
        tracker.onLaidOut(containerPx = 600f, fixedPx = 200f)
        tracker.start()

        assertEquals(210f, tracker.drag(10f, first))
        assertEquals(230f, tracker.drag(20f, first))
        assertNull(tracker.drag(0f, first))
        assertEquals(80f, tracker.drag(-1000f, first), "clamped at the minimum")
        assertNull(tracker.drag(-10f, first), "still beyond the minimum")
        assertEquals(390f, tracker.drag(1170f, first), "follows the pointer back: 200 + 10 + 20 - 1000 - 10 + 1170")
    }

    @Test
    fun `a new drag starts from the latest layout`() {
        val tracker = SplitDragTracker()
        tracker.onLaidOut(600f, 200f)
        tracker.start()
        tracker.drag(50f, second)

        tracker.onLaidOut(600f, 150f)
        tracker.start()

        assertEquals(140f, tracker.drag(10f, second))
    }
}
