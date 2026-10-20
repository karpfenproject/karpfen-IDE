package io.karpfen.ide.ui.editor.view.geometry

import androidx.compose.ui.geometry.Rect
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.state.ViewportSnapshot
import io.karpfen.ide.ui.editor.state.visibleLines

/**
 * The text area at one scroll position: [geometry] (content coordinates) moved by the scroll offsets of [viewport] into
 * text-area coordinates — the `EditorOverlayHost` space, where pointer positions arrive and popups are placed.
 * [laidOut] are the lines laid out for drawing (visible lines plus overscan). Immutable.
 */
internal class TextAreaFrame(val geometry: TextGeometry, val viewport: ViewportSnapshot) {

    /** The lines laid out for drawing (visible lines plus [EditorViewport.DEFAULT_OVERSCAN] on each side). */
    val laidOut: IntRange = viewport.visibleLines(geometry.snapshot.lineCount, EditorViewport.DEFAULT_OVERSCAN)

    /** Text-area x of content x [x]. */
    fun toAreaX(x: Float): Float = x - viewport.scrollX

    /** Text-area y of content y [y]. */
    fun toAreaY(y: Float): Float = y - viewport.scrollY

    /** The caret rectangle ([width] wide, one line tall) at [offset], whether visible or not. */
    fun caretRect(offset: Int, width: Float): Rect {
        val x = toAreaX(geometry.xOf(offset))
        val top = toAreaY(geometry.lineTop(geometry.lineOf(offset)))
        return Rect(x - width / 2f, top, x + width / 2f, top + geometry.lineHeight)
    }

    /** True if [rect] (text-area coordinates) intersects the visible area. */
    fun isVisible(rect: Rect): Boolean =
        rect.bottom > 0f && rect.top < viewport.viewportHeight && rect.right > 0f && rect.left < viewport.viewportWidth

    /** The rectangle of the character at [offset] (one cell wide), or null if its line is not laid out. */
    fun characterRect(offset: Int): Rect? {
        val line = geometry.lineOf(offset)
        if (line !in laidOut) return null
        val left = toAreaX(geometry.xOf(offset))
        val top = toAreaY(geometry.lineTop(line))
        val right = if (offset < geometry.snapshot.length && geometry.snapshot[offset] != '\n') {
            toAreaX(geometry.xOf(offset + 1))
        } else {
            left + geometry.charWidth
        }
        return Rect(left, top, maxOf(right, left + geometry.charWidth), top + geometry.lineHeight)
    }

    /** The caret position nearest to the text-area point ([x], [y]) (clicks). */
    fun offsetAt(x: Float, y: Float): Int = geometry.offsetAt(x + viewport.scrollX, y + viewport.scrollY)

    /** The character under the text-area point ([x], [y]), or null outside the text (hover). */
    fun characterAt(x: Float, y: Float): Int? = geometry.characterAt(x + viewport.scrollX, y + viewport.scrollY)
}
