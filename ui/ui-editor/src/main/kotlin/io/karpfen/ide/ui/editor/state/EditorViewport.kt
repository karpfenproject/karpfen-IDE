package io.karpfen.ide.ui.editor.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Scroll position and geometry of the editor, in pixels (spec §7.2). Compose-free: the view reports measured geometry
 * and renders from [state]; the scrollbar adapters and mouse wheel scroll through it. Scroll offsets are always clamped
 * to `0..max`.
 */
public interface EditorViewport {

    /** Current scroll offsets and geometry. */
    public val state: StateFlow<ViewportSnapshot>

    /** Reports the measured viewport size, line height and content size (after layout or text changes). */
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
     * Scrolls minimally so that the rectangle (content coordinates) is visible with a margin of three lines above and
     * below (spec §7.2), or centres it vertically when [center].
     */
    public fun reveal(left: Float, top: Float, right: Float, bottom: Float, center: Boolean)

    /** The lines to lay out: the visible lines of a [lineCount]-line text plus [overscan] lines on each side. */
    public fun visibleLines(lineCount: Int, overscan: Int = DEFAULT_OVERSCAN): IntRange

    /** Constants of [EditorViewport]. */
    public companion object {
        /** Lines laid out above and below the visible area. */
        public const val DEFAULT_OVERSCAN: Int = 5

        /** Lines kept visible above and below the caret when revealing it. */
        public const val CARET_MARGIN_LINES: Int = 3
    }
}

/** A new [EditorViewport] at scroll position (0, 0) with empty geometry. */
public fun EditorViewport(): EditorViewport = DefaultEditorViewport()

/** Scroll offsets (px) and geometry (px) of an [EditorViewport]. */
public data class ViewportSnapshot(
    public val scrollX: Float = 0f,
    public val scrollY: Float = 0f,
    public val viewportWidth: Float = 0f,
    public val viewportHeight: Float = 0f,
    public val lineHeight: Float = 0f,
    public val contentWidth: Float = 0f,
    public val contentHeight: Float = 0f,
) {
    /** Largest horizontal scroll offset. */
    public val maxScrollX: Float get() = maxOf(0f, contentWidth - viewportWidth)

    /** Largest vertical scroll offset. */
    public val maxScrollY: Float get() = maxOf(0f, contentHeight - viewportHeight)
}

/**
 * Default [EditorViewport]: one immutable [ViewportSnapshot] in a state flow, replaced by every geometry report and
 * scroll; offsets are clamped to `0..max` after every change (also when the content shrinks). Lines are laid out
 * uniformly: line `n` occupies `n * lineHeight until (n + 1) * lineHeight` in content coordinates.
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
                val margin = EditorViewport.CARET_MARGIN_LINES * current.lineHeight
                revealAxis(current.scrollY, current.viewportHeight, top - margin, bottom + margin)
            }
            val horizontalMargin = HORIZONTAL_MARGIN_LINES * current.lineHeight
            val x = revealAxis(current.scrollX, current.viewportWidth, left - horizontalMargin, right + horizontalMargin)
            current.copy(scrollX = x, scrollY = y).clamped()
        }
    }

    override fun visibleLines(lineCount: Int, overscan: Int): IntRange = snapshot.value.visibleLines(lineCount, overscan)

    /**
     * The smallest change of [scroll] (a viewport of [size]) that shows `start until end`; when the span is larger than
     * the viewport, its start wins.
     */
    private fun revealAxis(scroll: Float, size: Float, start: Float, end: Float): Float {
        var result = scroll
        if (end > result + size) result = end - size
        if (start < result) result = start
        return result
    }

    private fun ViewportSnapshot.clamped(): ViewportSnapshot =
        copy(scrollX = scrollX.coerceIn(0f, maxScrollX), scrollY = scrollY.coerceIn(0f, maxScrollY))

    private companion object {
        /** Horizontal context kept around a revealed caret, in line heights (about three characters). */
        const val HORIZONTAL_MARGIN_LINES = 2
    }
}

/**
 * The lines to lay out at this scroll position: the visible lines of a [lineCount]-line text plus [overscan] lines on
 * each side ([EditorViewport.visibleLines]); empty while no geometry is known.
 */
internal fun ViewportSnapshot.visibleLines(lineCount: Int, overscan: Int): IntRange {
    if (lineCount <= 0 || lineHeight <= 0f || viewportHeight <= 0f) return IntRange.EMPTY
    val first = floor(scrollY / lineHeight).toInt()
    val last = ceil((scrollY + viewportHeight) / lineHeight).toInt() - 1
    val from = (first - overscan).coerceIn(0, lineCount - 1)
    val to = (last + overscan).coerceIn(from, lineCount - 1)
    return from..to
}

/** The top of [line] in content coordinates (lines are [ViewportSnapshot.lineHeight] tall, line 0 at y = 0). */
internal fun ViewportSnapshot.lineTop(line: Int): Float = line * lineHeight

/** Number of lines that fit completely into the visible area (page size), at least 1. */
internal fun ViewportSnapshot.fullyVisibleLines(): Int =
    if (lineHeight <= 0f) 1 else floor(viewportHeight / lineHeight).toInt().coerceAtLeast(1)
