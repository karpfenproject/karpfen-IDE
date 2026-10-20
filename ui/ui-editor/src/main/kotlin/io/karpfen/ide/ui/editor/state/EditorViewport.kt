package io.karpfen.ide.ui.editor.state

import kotlinx.coroutines.flow.StateFlow

/**
 * Scroll position and geometry of the editor, in pixels (spec §7.2). Compose-free: the view reports measured geometry
 * and renders from [state]; the scrollbar adapters and mouse wheel scroll through it. Scroll offsets are always clamped
 * to `0..max`.
 */
public interface EditorViewport {

    /** Current scroll offsets and geometry. */
    public val state: StateFlow<ViewportState>

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
public data class ViewportState(
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

internal class DefaultEditorViewport : EditorViewport {
    override val state: StateFlow<ViewportState>
        get() = TODO("WP-EDITOR-VIEW: viewport state")

    override fun updateGeometry(
        viewportWidth: Float,
        viewportHeight: Float,
        lineHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Unit = TODO("WP-EDITOR-VIEW: geometry")

    override fun scrollBy(dx: Float, dy: Float): Unit = TODO("WP-EDITOR-VIEW: scroll by")

    override fun scrollTo(x: Float, y: Float): Unit = TODO("WP-EDITOR-VIEW: scroll to")

    override fun reveal(left: Float, top: Float, right: Float, bottom: Float, center: Boolean): Unit =
        TODO("WP-EDITOR-VIEW: reveal with margin")

    override fun visibleLines(lineCount: Int, overscan: Int): IntRange = TODO("WP-EDITOR-VIEW: visible lines")
}
