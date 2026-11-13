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

import androidx.compose.ui.Alignment

/**
 * Where a pane over the canvas lies and how large it is, in pixels from the top left corner of the canvas. The user
 * moves the pane by its head and sizes it by its borders and corners.
 *
 * @property x the left side.
 * @property y the upper side.
 * @property width the width.
 * @property height the height.
 */
internal data class PaneFrame(val x: Float, val y: Float, val width: Float, val height: Float) {

    /** This frame moved by [dx] and [dy]. */
    fun moved(dx: Float, dy: Float): PaneFrame = copy(x = x + dx, y = y + dy)

    /**
     * This frame with the sides of [grip] moved by [dx] and [dy]. The opposite sides stay where they are. The frame
     * never gets narrower than [minWidth] or lower than [minHeight]: a side that would cross that limit stops there.
     */
    fun resized(grip: PaneGrip, dx: Float, dy: Float, minWidth: Float, minHeight: Float): PaneFrame {
        val right = x + width
        val bottom = y + height
        val newLeft = if (grip.left) minOf(x + dx, right - minWidth) else x
        val newTop = if (grip.top) minOf(y + dy, bottom - minHeight) else y
        val newRight = if (grip.right) maxOf(right + dx, newLeft + minWidth) else right
        val newBottom = if (grip.bottom) maxOf(bottom + dy, newTop + minHeight) else bottom
        return PaneFrame(newLeft, newTop, newRight - newLeft, newBottom - newTop)
    }

    /**
     * This frame moved so that it lies inside a view that is [viewWidth] wide and [viewHeight] high, as far as it
     * fits. A frame larger than the view starts at the top left corner.
     */
    fun within(viewWidth: Float, viewHeight: Float): PaneFrame =
        copy(x = x.coerceIn(0f, maxOf(0f, viewWidth - width)), y = y.coerceIn(0f, maxOf(0f, viewHeight - height)))
}

/**
 * A hold on a pane by which the user sizes it: one of its four sides or one of its four corners.
 *
 * @property left true if the hold moves the left side.
 * @property top true if the hold moves the upper side.
 * @property right true if the hold moves the right side.
 * @property bottom true if the hold moves the lower side.
 * @property alignment where in the pane the hold lies.
 */
internal enum class PaneGrip(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean, val alignment: Alignment) {
    /** The left side. */
    LEFT(true, false, false, false, Alignment.CenterStart),

    /** The upper side. */
    TOP(false, true, false, false, Alignment.TopCenter),

    /** The right side. */
    RIGHT(false, false, true, false, Alignment.CenterEnd),

    /** The lower side. */
    BOTTOM(false, false, false, true, Alignment.BottomCenter),

    /** The upper left corner. */
    TOP_LEFT(true, true, false, false, Alignment.TopStart),

    /** The upper right corner. */
    TOP_RIGHT(false, true, true, false, Alignment.TopEnd),

    /** The lower left corner. */
    BOTTOM_LEFT(true, false, false, true, Alignment.BottomStart),

    /** The lower right corner. */
    BOTTOM_RIGHT(false, false, true, true, Alignment.BottomEnd),
    ;

    /** True for a corner, which moves two sides. */
    val isCorner: Boolean get() = (left || right) && (top || bottom)
}
