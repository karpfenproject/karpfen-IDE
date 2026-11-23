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
package io.karpfen.ide.ui.editor.view.geometry

import androidx.compose.ui.geometry.Rect
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.state.ViewportSnapshot
import io.karpfen.ide.ui.editor.state.visibleLines

/**
 * The text area at one scroll position. [geometry] uses content coordinates. This class moves them by the scroll
 * offsets of [viewport] into text area coordinates. That is the `EditorOverlayHost` space, where pointer positions
 * arrive and popups are placed. [laidOut] holds the lines laid out for drawing, which are the visible lines plus
 * overscan. Instances are immutable.
 */
internal class TextAreaFrame(val geometry: TextGeometry, val viewport: ViewportSnapshot) {

    /**
     * The lines laid out for drawing. These are the lines on the visible rows plus [EditorViewport.DEFAULT_OVERSCAN]
     * rows on each side. A line drawn on several rows, the line of a completion preview, counts once.
     */
    val laidOut: IntRange = viewport.visibleLines(geometry.rowCount, EditorViewport.DEFAULT_OVERSCAN).let { rows ->
        if (rows.isEmpty()) rows else geometry.shift.lineAt(rows.first)..geometry.shift.lineAt(rows.last)
    }

    /** Returns the text area x of the content x [x]. */
    fun toAreaX(x: Float): Float = x - viewport.scrollX

    /** Returns the text area y of the content y [y]. */
    fun toAreaY(y: Float): Float = y - viewport.scrollY

    /** Returns the caret rectangle at [offset], [width] wide and one row tall, whether it is visible or not. */
    fun caretRect(offset: Int, width: Float): Rect {
        val x = toAreaX(geometry.xOf(offset))
        val top = toAreaY(geometry.rowTop(geometry.rowOf(offset)))
        return Rect(x - width / 2f, top, x + width / 2f, top + geometry.lineHeight)
    }

    /**
     * Returns [caret], the caret rectangle at [offset], extended down to the bottom of the last row that shows the line
     * of [offset]. On the line of a completion preview that is the last row of the preview, so a popup placed below it
     * does not cover the preview. On any other line it is [caret] itself.
     */
    fun belowLineOf(offset: Int, caret: Rect): Rect {
        val bottom = toAreaY(geometry.rowTop(geometry.shift.lastRowOf(geometry.lineOf(offset))) + geometry.lineHeight)
        return if (bottom > caret.bottom) Rect(caret.left, caret.top, caret.right, bottom) else caret
    }

    /** True if [rect], given in text area coordinates, intersects the visible area. */
    fun isVisible(rect: Rect): Boolean =
        rect.bottom > 0f && rect.top < viewport.viewportHeight && rect.right > 0f && rect.left < viewport.viewportWidth

    /**
     * Returns the rectangle of the character at [offset], or null if its line is not laid out. The rectangle is at
     * least one cell wide, so a tab covers its full width and a line end covers one cell.
     */
    fun characterRect(offset: Int): Rect? {
        val line = geometry.lineOf(offset)
        if (line !in laidOut) return null
        val left = toAreaX(geometry.cellXOf(offset))
        val top = toAreaY(geometry.rowTop(geometry.cellRowOf(offset)))
        val right = if (offset < geometry.snapshot.length && geometry.snapshot[offset] != '\n') {
            toAreaX(geometry.xOf(offset + 1))
        } else {
            left + geometry.charWidth
        }
        return Rect(left, top, maxOf(right, left + geometry.charWidth), top + geometry.lineHeight)
    }

    /** Returns the caret position nearest to the text area point at [x] and [y]. Clicks use it. */
    fun offsetAt(x: Float, y: Float): Int = geometry.offsetAt(x + viewport.scrollX, y + viewport.scrollY)

    /** Returns the character under the text area point at [x] and [y], or null outside the text. Hover uses it. */
    fun characterAt(x: Float, y: Float): Int? = geometry.characterAt(x + viewport.scrollX, y + viewport.scrollY)
}
