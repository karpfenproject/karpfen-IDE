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
package io.karpfen.ide.ui.foundation.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The minimal scrolling of [ScrollReveal] for visible, earlier, later and oversized items. */
class ScrollRevealTest {

    @Test
    fun `a fully visible item needs no scrolling`() {
        assertNull(ScrollReveal.target(itemStart = 100, itemEnd = 120, viewportStart = 50, viewportSize = 200))
        assertNull(ScrollReveal.target(itemStart = 50, itemEnd = 250, viewportStart = 50, viewportSize = 200), "exactly fitting")
    }

    @Test
    fun `an item above the viewport is aligned with its start`() {
        assertEquals(10, ScrollReveal.target(itemStart = 10, itemEnd = 30, viewportStart = 50, viewportSize = 200))
        assertEquals(40, ScrollReveal.target(itemStart = 40, itemEnd = 60, viewportStart = 50, viewportSize = 200), "partly visible")
    }

    @Test
    fun `an item below the viewport is aligned with its end`() {
        assertEquals(330, ScrollReveal.target(itemStart = 510, itemEnd = 530, viewportStart = 50, viewportSize = 200))
        assertEquals(60, ScrollReveal.target(itemStart = 240, itemEnd = 260, viewportStart = 50, viewportSize = 200), "partly visible")
    }

    @Test
    fun `an item larger than the viewport is aligned with its start`() {
        assertEquals(400, ScrollReveal.target(itemStart = 400, itemEnd = 700, viewportStart = 50, viewportSize = 200))
        assertEquals(10, ScrollReveal.target(itemStart = 10, itemEnd = 400, viewportStart = 50, viewportSize = 200))
    }
}
