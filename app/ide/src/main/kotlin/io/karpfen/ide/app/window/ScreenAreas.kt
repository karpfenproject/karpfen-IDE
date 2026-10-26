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
package io.karpfen.ide.app.window

import androidx.compose.ui.unit.DpSize
import java.awt.GraphicsEnvironment

/** A screen's bounds in the window system's coordinates (dp on the desktop). */
internal data class ScreenArea(val x: Int, val y: Int, val width: Int, val height: Int) {

    /**
     * True if a window at ([windowX], [windowY]) of [size] has its title bar on this screen: its top edge within the
     * screen (leaving [TITLE_BAR_HEIGHT]) and at least [MINIMUM_VISIBLE_WIDTH] of its width over the screen, so it can
     * be dragged back.
     */
    fun showsTitleBarOf(windowX: Int, windowY: Int, size: DpSize): Boolean {
        val windowWidth = size.width.value.toInt()
        val overlap = minOf(windowX + windowWidth, x + width) - maxOf(windowX, x)
        val visibleWidth = minOf(MINIMUM_VISIBLE_WIDTH, windowWidth)
        return windowY >= y && windowY <= y + height - TITLE_BAR_HEIGHT && overlap >= visibleWidth
    }

    private companion object {
        /** The height of a window's title bar, which must fit above the screen's bottom edge. */
        const val TITLE_BAR_HEIGHT = 24

        /** The width of a window that must lie over the screen, so the window can be dragged back. */
        const val MINIMUM_VISIBLE_WIDTH = 80
    }
}

/** The screens a window can be placed on (the seam between window placement and AWT). */
internal fun interface ScreenAreas {
    /** The current screens; empty if unknown. */
    fun current(): List<ScreenArea>
}

/** The screens of the local graphics environment; none in a headless JVM. */
internal fun awtScreenAreas(): ScreenAreas = ScreenAreas {
    if (GraphicsEnvironment.isHeadless()) {
        emptyList()
    } else {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val bounds = device.defaultConfiguration.bounds
            ScreenArea(bounds.x, bounds.y, bounds.width, bounds.height)
        }
    }
}
