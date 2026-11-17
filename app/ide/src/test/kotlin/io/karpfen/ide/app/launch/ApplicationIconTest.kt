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

import java.awt.AWTEvent
import java.awt.Color
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.AWTEventListenerProxy
import java.awt.event.HierarchyEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Tests that the application's icon is among the resources and usable as an icon, that the windows get it in every
 * size from one listener, and that the scaling keeps the image. The tests run headless, where no window can be made, so
 * the hand-over to a real window is not tested here.
 */
class ApplicationIconTest {

    @Test
    fun `the icon of the window is a square image without empty corners`() {
        val icon = assertNotNull(ApplicationIcon.load(ApplicationIcon.WINDOW), "no image at ${ApplicationIcon.WINDOW}")

        assertEquals(icon.width, icon.height, "the icon is not square")
        assertTrue(icon.width >= 512, "the icon is only ${icon.width} px wide")
        assertEquals(OPAQUE, icon.getRGB(0, 0) ushr ALPHA_SHIFT, "the corner is not opaque")
    }

    @Test
    fun `the window icon comes in every size the desktops ask for, square and drawn`() {
        val images = ApplicationIcon.windowImages

        assertEquals(ApplicationIcon.WINDOW_SIZES, images.map { it.width })
        assertEquals(ApplicationIcon.WINDOW_SIZES, images.map { it.height })
        images.forEach { image ->
            val size = image.width
            val alpha = { x: Int, y: Int -> image.getRGB(x, y) ushr ALPHA_SHIFT }
            assertEquals(OPAQUE, alpha(0, 0), "the corner of the $size px icon is not opaque")
            assertEquals(OPAQUE, alpha(size - 1, size - 1), "the far corner of the $size px icon is not opaque")
            assertEquals(OPAQUE, alpha(size / 2, size / 2), "the middle of the $size px icon is not opaque")
            val pixels = image.getRGB(0, 0, size, size, null, 0, size)
            assertTrue(pixels.distinct().size > 1, "the $size px icon is a single colour")
        }
    }

    @Test
    fun `scaling keeps the colour and always gives a new translucent image`() {
        val source = BufferedImage(SOURCE, SOURCE, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().run {
            color = Color.RED
            fillRect(0, 0, SOURCE, SOURCE)
            dispose()
        }

        listOf(16, 48, SOURCE / 2, SOURCE).forEach { size ->
            val image = ApplicationIcon.scaled(source, size)
            assertEquals(size to size, image.width to image.height)
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
            assertNotSame(source, image)
            assertEquals(Color.RED.rgb, image.getRGB(size / 2, size / 2), "the $size px image changed colour")
        }
    }

    @Test
    fun `the window icon is handed to the windows by one listener, however often it is asked for`() {
        val toolkit = Toolkit.getDefaultToolkit()

        ApplicationIcon.showOnEveryWindow(toolkit)
        assertFalse(ApplicationIcon.showOnEveryWindow(toolkit))

        assertTrue(WindowIcons.isInstalled(toolkit))
        assertEquals(1, toolkit.getAWTEventListeners(AWTEvent.HIERARCHY_EVENT_MASK).count(::isWindowIcons))
    }

    @Test
    fun `without images no listener is installed`() {
        val toolkit = Toolkit.getDefaultToolkit()
        val before = toolkit.getAWTEventListeners(AWTEvent.HIERARCHY_EVENT_MASK).count(::isWindowIcons)

        assertFalse(WindowIcons(emptyList()).install(toolkit))
        assertEquals(before, toolkit.getAWTEventListeners(AWTEvent.HIERARCHY_EVENT_MASK).count(::isWindowIcons))
    }

    @Test
    fun `an event about a component that is not a window is ignored`() {
        val panel = JPanel()

        val flags = HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()

        WindowIcons(ApplicationIcon.windowImages).eventDispatched(
            HierarchyEvent(panel, HierarchyEvent.HIERARCHY_CHANGED, panel, null, flags),
        )
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
        /** The side of the synthetic image the scaling is tested with, in pixels. */
        const val SOURCE = 100

        /** Whether [listener], as the toolkit hands it out, is a [WindowIcons] listener. */
        fun isWindowIcons(listener: AWTEventListener): Boolean =
            (listener as? AWTEventListenerProxy)?.listener is WindowIcons || listener is WindowIcons

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
