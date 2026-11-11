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
package io.karpfen.ide.ui.editor.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The scroll position and geometry of the editor, in pixels. It does not need Compose. The view reports the measured
 * geometry and renders from [state]. The scrollbar adapters and the mouse wheel scroll through it. Scroll offsets are
 * always clamped to `0..max`.
 */
public interface EditorViewport {

    /** The current scroll offsets and geometry. */
    public val state: StateFlow<ViewportSnapshot>

    /** Reports the measured viewport size, line height and content size. The view calls it after layout and text changes. */
    public fun updateGeometry(
        viewportWidth: Float,
        viewportHeight: Float,
        lineHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    )

    /** Scrolls by the given deltas. */
    public fun scrollBy(dx: Float, dy: Float)

    /** Scrolls to the given offsets. */
    public fun scrollTo(x: Float, y: Float)

    /**
     * Scrolls as little as possible so that the rectangle is visible with a margin of three lines above and below it.
     * The rectangle is given in content coordinates. If [center] is true, it centres the rectangle vertically instead.
     * In a viewport too small for the margin, the margin shrinks to what fits around the rectangle, so the rectangle
     * itself is always revealed.
     */
    public fun reveal(left: Float, top: Float, right: Float, bottom: Float, center: Boolean)

    /** Returns the lines to lay out. These are the visible lines of a text with [lineCount] lines plus [overscan] lines on each side. */
    public fun visibleLines(lineCount: Int, overscan: Int = DEFAULT_OVERSCAN): IntRange

    /** Constants of [EditorViewport]. */
    public companion object {
        /** The number of lines laid out above and below the visible area. */
        public const val DEFAULT_OVERSCAN: Int = 5

        /** The number of lines kept visible above and below the caret when it is revealed. */
        public const val CARET_MARGIN_LINES: Int = 3
    }
}

/** Creates an [EditorViewport] at scroll position 0, 0 with empty geometry. */
public fun EditorViewport(): EditorViewport = DefaultEditorViewport()

/**
 * The scroll offsets and geometry of an [EditorViewport], all in pixels.
 *
 * @property scrollX the content offset at the viewport's left edge.
 * @property scrollY the content offset at the viewport's top edge.
 * @property viewportWidth the width of the visible area.
 * @property viewportHeight the height of the visible area.
 * @property lineHeight the height of every line.
 * @property contentWidth the width of the widest laid-out content.
 * @property contentHeight the height of all lines.
 */
public data class ViewportSnapshot(
    public val scrollX: Float = 0f,
    public val scrollY: Float = 0f,
    public val viewportWidth: Float = 0f,
    public val viewportHeight: Float = 0f,
    public val lineHeight: Float = 0f,
    public val contentWidth: Float = 0f,
    public val contentHeight: Float = 0f,
) {
    /** The largest horizontal scroll offset. */
    public val maxScrollX: Float get() = maxOf(0f, contentWidth - viewportWidth)

    /** The largest vertical scroll offset. */
    public val maxScrollY: Float get() = maxOf(0f, contentHeight - viewportHeight)
}

/**
 * The default [EditorViewport]. It keeps one immutable [ViewportSnapshot] in a state flow and replaces it on every
 * geometry report and every scroll. Offsets are clamped to `0..max` after every change, also when the content shrinks.
 * All lines have the same height. Line `n` occupies `n * lineHeight until (n + 1) * lineHeight` in content
 * coordinates.
 */
internal class DefaultEditorViewport : EditorViewport {

    private val snapshot = MutableStateFlow(ViewportSnapshot())

    override val state: StateFlow<ViewportSnapshot> = snapshot.asStateFlow()

    override fun updateGeometry(
        viewportWidth: Float,
        viewportHeight: Float,
        lineHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ) {
        snapshot.update {
            it.copy(
                viewportWidth = viewportWidth.coerceAtLeast(0f),
                viewportHeight = viewportHeight.coerceAtLeast(0f),
                lineHeight = lineHeight.coerceAtLeast(0f),
                contentWidth = contentWidth.coerceAtLeast(0f),
                contentHeight = contentHeight.coerceAtLeast(0f),
            ).clamped()
        }
    }

    override fun scrollBy(dx: Float, dy: Float) {
        snapshot.update { it.copy(scrollX = it.scrollX + dx, scrollY = it.scrollY + dy).clamped() }
    }

    override fun scrollTo(x: Float, y: Float) {
        snapshot.update { it.copy(scrollX = x, scrollY = y).clamped() }
    }

    override fun reveal(left: Float, top: Float, right: Float, bottom: Float, center: Boolean) {
        snapshot.update { current ->
            val y = if (center) {
                (top + bottom) / 2f - current.viewportHeight / 2f
            } else {
                val margin = fittingMargin(EditorViewport.CARET_MARGIN_LINES * current.lineHeight, current.viewportHeight, bottom - top)
                revealAxis(current.scrollY, current.viewportHeight, top - margin, bottom + margin)
            }
            val horizontalMargin = fittingMargin(HORIZONTAL_MARGIN_LINES * current.lineHeight, current.viewportWidth, right - left)
            val x = revealAxis(current.scrollX, current.viewportWidth, left - horizontalMargin, right + horizontalMargin)
            current.copy(scrollX = x, scrollY = y).clamped()
        }
    }

    override fun visibleLines(lineCount: Int, overscan: Int): IntRange = snapshot.value.visibleLines(lineCount, overscan)

    /**
     * Returns the smallest change of [scroll] for a viewport of [size] that shows `start until end`. When the span is
     * larger than the viewport, its start wins.
     */
    private fun revealAxis(scroll: Float, size: Float, start: Float, end: Float): Float {
        var result = scroll
        if (end > result + size) result = end - size
        if (start < result) result = start
        return result
    }

    /**
     * Returns the [preferred] margin, reduced so that a rectangle of [extent] with the margin on both sides fits into a
     * viewport of [size]. The result is never negative.
     */
    private fun fittingMargin(preferred: Float, size: Float, extent: Float): Float =
        minOf(preferred, ((size - extent) / 2f).coerceAtLeast(0f))

    /** This snapshot with both scroll offsets clamped to `0..max`. */
    private fun ViewportSnapshot.clamped(): ViewportSnapshot =
        copy(scrollX = scrollX.coerceIn(0f, maxScrollX), scrollY = scrollY.coerceIn(0f, maxScrollY))

    /** The horizontal reveal margin. */
    private companion object {
        /** The horizontal space kept around a revealed caret, in line heights. That is about three characters. */
        const val HORIZONTAL_MARGIN_LINES = 2
    }
}

/**
 * Returns the lines to lay out at this scroll position, as described on [EditorViewport.visibleLines]. These are the
 * visible lines of a text with [lineCount] lines plus [overscan] lines on each side. The range is empty while no
 * geometry is known.
 */
internal fun ViewportSnapshot.visibleLines(lineCount: Int, overscan: Int): IntRange {
    if (lineCount <= 0 || lineHeight <= 0f || viewportHeight <= 0f) return IntRange.EMPTY
    val first = floor(scrollY / lineHeight).toInt()
    val last = ceil((scrollY + viewportHeight) / lineHeight).toInt() - 1
    val from = (first - overscan).coerceIn(0, lineCount - 1)
    val to = (last + overscan).coerceIn(from, lineCount - 1)
    return from..to
}

/** Returns the top of [line] in content coordinates. Every line is [ViewportSnapshot.lineHeight] tall, and line 0 starts at y = 0. */
internal fun ViewportSnapshot.lineTop(line: Int): Float = line * lineHeight

/** Returns the number of lines that fit completely into the visible area, which is the page size. It is at least 1. */
internal fun ViewportSnapshot.fullyVisibleLines(): Int =
    if (lineHeight <= 0f) 1 else floor(viewportHeight / lineHeight).toInt().coerceAtLeast(1)
