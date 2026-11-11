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
package io.karpfen.ide.ui.editor.state

import kotlin.test.Test
import kotlin.test.assertEquals

/** The scroll geometry of [EditorViewport]: clamping, revealing with the caret margin, centring and the visible lines. */
class EditorViewportTest {

    /** 100 lines of 20 px in a viewport 300 px wide and 200 px high. The content is 1000 px wide. */
    private fun viewport(): EditorViewport = EditorViewport().apply {
        updateGeometry(viewportWidth = 300f, viewportHeight = 200f, lineHeight = 20f, contentWidth = 1000f, contentHeight = 2000f)
    }

    @Test
    fun `starts at the origin without geometry and lays out nothing`() {
        val viewport = EditorViewport()

        assertEquals(ViewportSnapshot(), viewport.state.value)
        assertEquals(IntRange.EMPTY, viewport.visibleLines(100))
    }

    @Test
    fun `scroll offsets are clamped to the content`() {
        val viewport = viewport()

        viewport.scrollBy(-10f, -10f)
        assertEquals(0f to 0f, viewport.state.value.let { it.scrollX to it.scrollY })
        viewport.scrollTo(5000f, 5000f)
        assertEquals(700f to 1800f, viewport.state.value.let { it.scrollX to it.scrollY })
        viewport.updateGeometry(300f, 200f, 20f, contentWidth = 400f, contentHeight = 500f)
        assertEquals(100f to 300f, viewport.state.value.let { it.scrollX to it.scrollY }, "a shrinking content clamps again")
    }

    @Test
    fun `reveal scrolls minimally with a margin of three lines`() {
        val viewport = viewport()

        viewport.reveal(10f, 300f, 12f, 320f, center = false)
        assertEquals(320f + 60f - 200f, viewport.state.value.scrollY, "below: the line and three more at the bottom")
        viewport.reveal(10f, 300f, 12f, 320f, center = false)
        assertEquals(180f, viewport.state.value.scrollY, "already visible with its margin: no change")
        viewport.reveal(10f, 100f, 12f, 120f, center = false)
        assertEquals(100f - 60f, viewport.state.value.scrollY, "above: three lines above it stay visible")
        viewport.reveal(10f, 0f, 12f, 20f, center = false)
        assertEquals(0f, viewport.state.value.scrollY, "clamped at the top")
    }

    @Test
    fun `the reveal margin shrinks to what the viewport can show around the rectangle`() {
        val viewport = EditorViewport().apply {
            updateGeometry(viewportWidth = 30f, viewportHeight = 60f, lineHeight = 20f, contentWidth = 1000f, contentHeight = 2000f)
        }

        viewport.reveal(10f, 200f, 12f, 220f, center = false)
        assertEquals(180f, viewport.state.value.scrollY, "one line above and below: the margin that fits into three lines")
        viewport.reveal(10f, 400f, 12f, 420f, center = false)
        assertEquals(380f, viewport.state.value.scrollY)
        viewport.reveal(500f, 400f, 502f, 420f, center = false)
        val x = viewport.state.value.scrollX
        assertEquals(true, 500f >= x && 502f <= x + 30f, "the caret is inside the narrow viewport horizontally (scrollX $x)")
    }

    @Test
    fun `reveal centres vertically on request and reveals horizontally`() {
        val viewport = viewport()

        viewport.reveal(500f, 1000f, 502f, 1020f, center = true)

        assertEquals(1010f - 100f, viewport.state.value.scrollY)
        assertEquals(502f + 40f - 300f, viewport.state.value.scrollX, "the right edge plus two line heights of context")
        viewport.reveal(10f, 1000f, 12f, 1020f, center = true)
        assertEquals(0f, viewport.state.value.scrollX)
    }

    @Test
    fun `visible lines include the overscan on both sides and stay in the text`() {
        val viewport = viewport()

        assertEquals(0..14, viewport.visibleLines(100))
        viewport.scrollTo(0f, 410f)
        assertEquals(15..35, viewport.visibleLines(100), "lines 20 to 30 are (partly) visible")
        assertEquals(20..30, viewport.visibleLines(100, overscan = 0))
        assertEquals(15..24, viewport.visibleLines(25))
        assertEquals(IntRange.EMPTY, viewport.visibleLines(0))
    }

    @Test
    fun `the page size is the number of fully visible lines`() {
        assertEquals(10, viewport().state.value.fullyVisibleLines())
        assertEquals(1, ViewportSnapshot().fullyVisibleLines())
    }
}
