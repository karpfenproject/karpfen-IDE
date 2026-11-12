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
package io.karpfen.ide.ui.workbench.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the steps and the limits of the two sizes the status bar's zoom controls change. */
class ZoomTest {

    @Test
    fun `the workbench scale moves in tenths and does not drift`() {
        assertEquals(1.1f, Zoom.uiScale(1f, ZoomStep.IN))
        assertEquals(0.9f, Zoom.uiScale(1f, ZoomStep.OUT))
        assertEquals(1f, Zoom.uiScale(1.7f, ZoomStep.RESET))

        var scale = 1f
        repeat(STEPS) { scale = Zoom.uiScale(scale, ZoomStep.IN) }
        repeat(STEPS) { scale = Zoom.uiScale(scale, ZoomStep.OUT) }
        assertEquals(1f, scale, "five steps up and five steps down end where they started")
    }

    @Test
    fun `the workbench scale stays within its limits`() {
        assertEquals(Zoom.MAX_UI_SCALE, Zoom.uiScale(Zoom.MAX_UI_SCALE, ZoomStep.IN))
        assertEquals(Zoom.MIN_UI_SCALE, Zoom.uiScale(Zoom.MIN_UI_SCALE, ZoomStep.OUT))
        assertEquals(Zoom.MAX_UI_SCALE, Zoom.uiScale(9f, ZoomStep.OUT), "a value from a hand-edited file comes back into the limits")
    }

    @Test
    fun `the editor font size moves in whole points within its limits`() {
        assertEquals(14f, Zoom.editorFontSize(13f, ZoomStep.IN))
        assertEquals(12f, Zoom.editorFontSize(13f, ZoomStep.OUT))
        assertEquals(15f, Zoom.editorFontSize(13.6f, ZoomStep.IN), "a fraction from a hand-edited file is rounded first")
        assertEquals(Zoom.DEFAULT_EDITOR_FONT_SIZE, Zoom.editorFontSize(22f, ZoomStep.RESET))
        assertEquals(Zoom.MAX_EDITOR_FONT_SIZE, Zoom.editorFontSize(Zoom.MAX_EDITOR_FONT_SIZE, ZoomStep.IN))
        assertEquals(Zoom.MIN_EDITOR_FONT_SIZE, Zoom.editorFontSize(Zoom.MIN_EDITOR_FONT_SIZE, ZoomStep.OUT))
    }

    @Test
    fun `the scale reads as a percentage`() {
        assertEquals("100%", Zoom.percent(1f))
        assertEquals("70%", Zoom.percent(0.7f))
        assertEquals("130%", Zoom.percent(1.3f))
    }

    private companion object {
        /** How many steps the drift test goes up and down. */
        const val STEPS = 5
    }
}
