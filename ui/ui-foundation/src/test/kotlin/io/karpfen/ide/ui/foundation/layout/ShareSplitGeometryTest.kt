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
package io.karpfen.ide.ui.foundation.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The size rules of [ShareSplitGeometry]: parts by share, the bounds of the share, a container without space, divider
 * drags, and the [ShareDragTracker] that follows the pointer from the press.
 */
class ShareSplitGeometryTest {

    private val geometry = ShareSplitGeometry(minShare = 0.1f, maxShare = 0.9f, divider = 1f)

    @Test
    fun `the first part takes its share of the space next to the divider and the second the rest`() {
        assertEquals(SplitSizes(300f, 300f), geometry.layout(container = 601f, share = 0.5f))
        assertEquals(SplitSizes(150f, 450f), geometry.layout(container = 601f, share = 0.25f))
    }

    @Test
    fun `a share beyond the bounds is laid out at the bound`() {
        assertEquals(SplitSizes(10f, 90f), geometry.layout(container = 101f, share = 0.01f))
        assertEquals(SplitSizes(90f, 10f), geometry.layout(container = 101f, share = 1f))
    }

    @Test
    fun `a container without space gives both parts nothing`() {
        assertEquals(SplitSizes(0f, 0f), geometry.layout(container = 0f, share = 0.5f))
        assertEquals(0.3f, geometry.dragged(container = 0f, start = 0f, delta = 40f, fallback = 0.3f))
    }

    @Test
    fun `a drag moves the share by the part of the space the pointer moved`() {
        assertEquals(0.75f, geometry.dragged(container = 401f, start = 200f, delta = 100f, fallback = 0.5f))
        assertEquals(0.25f, geometry.dragged(container = 401f, start = 200f, delta = -100f, fallback = 0.5f))
        assertEquals(0.9f, geometry.dragged(container = 401f, start = 200f, delta = 1000f, fallback = 0.5f), "kept at the bound")
    }

    @Test
    fun `bounds that cross are a programming error`() {
        assertFailsWith<IllegalArgumentException> { ShareSplitGeometry(minShare = 0.6f, maxShare = 0.4f, divider = 1f) }
    }

    @Test
    fun `the tracker adds up the movement since the press and reports only changes`() {
        val tracker = ShareDragTracker()
        tracker.onLaidOut(containerPx = 401f, firstPx = 200f, share = 0.5f)
        tracker.start()

        assertEquals(0.625f, tracker.drag(50f, geometry))
        assertEquals(0.75f, tracker.drag(50f, geometry))
        assertNull(tracker.drag(0f, geometry), "no movement, no report")
        assertEquals(0.9f, tracker.drag(500f, geometry))
        assertNull(tracker.drag(10f, geometry), "beyond the bound the share stays")
        assertEquals(0.5f, tracker.drag(-610f, geometry), "back where the drag started")
    }
}
