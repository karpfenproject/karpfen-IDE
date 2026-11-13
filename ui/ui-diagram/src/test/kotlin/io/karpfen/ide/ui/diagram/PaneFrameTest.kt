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
package io.karpfen.ide.ui.diagram

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests how a pane over the canvas is moved, sized and kept inside the view. */
class PaneFrameTest {

    private val frame = PaneFrame(100f, 50f, 400f, 200f)

    @Test
    fun `a hold moves its sides and leaves the opposite ones`() {
        assertEquals(PaneFrame(100f, 50f, 460f, 230f), frame.resized(PaneGrip.BOTTOM_RIGHT, 60f, 30f, 200f, 100f))
        assertEquals(PaneFrame(70f, 50f, 430f, 200f), frame.resized(PaneGrip.LEFT, -30f, 99f, 200f, 100f))
        assertEquals(PaneFrame(100f, 20f, 400f, 230f), frame.resized(PaneGrip.TOP, 99f, -30f, 200f, 100f))
        assertEquals(PaneFrame(120f, 70f, 380f, 180f), frame.resized(PaneGrip.TOP_LEFT, 20f, 20f, 200f, 100f))
    }

    @Test
    fun `a side stops where the pane would get too small`() {
        assertEquals(PaneFrame(100f, 50f, 200f, 100f), frame.resized(PaneGrip.BOTTOM_RIGHT, -900f, -900f, 200f, 100f))
        assertEquals(PaneFrame(300f, 150f, 200f, 100f), frame.resized(PaneGrip.TOP_LEFT, 900f, 900f, 200f, 100f))
    }

    @Test
    fun `a pane is moved, and kept inside the view as far as it fits`() {
        assertEquals(PaneFrame(130f, 40f, 400f, 200f), frame.moved(30f, -10f))
        assertEquals(PaneFrame(200f, 100f, 400f, 200f), PaneFrame(900f, 900f, 400f, 200f).within(600f, 300f))
        assertEquals(PaneFrame(0f, 0f, 400f, 200f), PaneFrame(-50f, 80f, 400f, 200f).within(300f, 150f), "a pane larger than the view starts at its corner")
    }
}
