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
package io.karpfen.ide.app.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests that the application's icon is among the resources and usable as an icon. */
class ApplicationIconTest {

    @Test
    fun `the icon of the window is a square image without empty corners`() {
        val icon = assertNotNull(ApplicationIcon.load(ApplicationIcon.WINDOW), "no image at ${ApplicationIcon.WINDOW}")

        assertEquals(icon.width, icon.height, "the icon is not square")
        assertTrue(icon.width >= 512, "the icon is only ${icon.width} px wide")
        assertEquals(OPAQUE, icon.getRGB(0, 0) ushr ALPHA_SHIFT, "the corner is not opaque")
        assertNotNull(ApplicationIcon.painter())
    }

    @Test
    fun `the icon of the Dock has the shape of a Mac application icon`() {
        val icon = assertNotNull(ApplicationIcon.load(ApplicationIcon.DOCK), "no image at ${ApplicationIcon.DOCK}")
        val alpha = { x: Int, y: Int -> icon.getRGB(x, y) ushr ALPHA_SHIFT }

        assertEquals(CANVAS to CANVAS, icon.width to icon.height)
        assertEquals(0, alpha(0, 0), "the canvas around the icon is not empty")
        assertEquals(0, alpha(MARGIN / 2, CANVAS / 2), "the margin left of the icon is not empty")
        assertEquals(OPAQUE, alpha(CANVAS / 2, MARGIN + 1), "the icon does not start right below the margin")
        assertEquals(OPAQUE, alpha(CANVAS / 2, CANVAS / 2), "the middle of the icon is not opaque")
        assertTrue(alpha(MARGIN + 8, MARGIN + 8) < OPAQUE, "the corner of the icon is not rounded")
    }

    @Test
    fun `a headless JVM is left alone`() {
        ApplicationIcon.showInTaskbar()
        ApplicationIcon.showInTaskbar(null)
    }

    private companion object {
        /** The side of the canvas of a Mac application icon in pixels. */
        const val CANVAS = 1024

        /** The empty margin around the rounded square of a Mac application icon in pixels. */
        const val MARGIN = 100

        /** The alpha of an opaque pixel. */
        const val OPAQUE = 0xFF

        /** How far the alpha sits from the low end of a pixel's value. */
        const val ALPHA_SHIFT = 24
    }
}
