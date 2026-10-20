package io.karpfen.ide.ui.foundation.layout

/** The laid-out main-axis sizes of the two panes of a [SplitPane], in px. */
internal data class SplitSizes(val first: Float, val second: Float)

/**
 * The size rules of a [SplitPane] along its main axis, in px (pure; unit-tested without Compose): the [fixedSide] pane
 * keeps its requested size, clamped so that neither pane shrinks below its minimum and both fit next to the divider
 * of [divider] px; the other pane takes the rest. When the container cannot hold both minimums, the fixed pane keeps
 * its minimum as long as it fits into the container and the other pane shrinks.
 */
internal data class SplitGeometry(
    val fixedSide: SplitSide,
    val minFirst: Float,
    val minSecond: Float,
    val divider: Float,
) {
    private val minFixed: Float get() = if (fixedSide == SplitSide.FIRST) minFirst else minSecond
    private val minOther: Float get() = if (fixedSide == SplitSide.FIRST) minSecond else minFirst

    /** The fixed pane's size for a requested [fixed] size in a container of [container] px. */
    fun fixedSize(container: Float, fixed: Float): Float {
        val available = (container - divider).coerceAtLeast(0f)
        val upper = (available - minOther).coerceAtLeast(minFixed)
        return fixed.coerceIn(minFixed, upper).coerceAtMost(available)
    }

    /** The sizes of both panes for a requested [fixed] size in a container of [container] px. */
    fun layout(container: Float, fixed: Float): SplitSizes {
        val fixedPane = fixedSize(container, fixed)
        val otherPane = (container - divider - fixedPane).coerceAtLeast(0f)
        return if (fixedSide == SplitSide.FIRST) SplitSizes(fixedPane, otherPane) else SplitSizes(otherPane, fixedPane)
    }

    /**
     * The fixed pane's size after the divider moved by [delta] px from where it was when the fixed pane had [start] px
     * (positive [delta]: right or down), clamped to the minimums in a container of [container] px.
     */
    fun dragged(container: Float, start: Float, delta: Float): Float {
        val requested = if (fixedSide == SplitSide.FIRST) start + delta else start - delta
        return fixedSize(container, requested)
    }

    /** The container size that holds a fixed pane of [fixed] px at minimum (used on an unbounded main axis). */
    fun naturalContainer(fixed: Float): Float = fixed.coerceAtLeast(minFixed) + divider + minOther
}

/**
 * Tracks one divider drag of a [SplitPane] (Compose-side helper state, used on the UI thread only): remembers the
 * last layout and accumulates the pointer movement since the press, so the divider follows the pointer exactly and
 * snaps back when the pointer returns from beyond a minimum.
 */
internal class SplitDragTracker {
    private var container = 0f
    private var laidOutFixed = 0f
    private var start = 0f
    private var total = 0f
    private var lastReported = 0f

    /** Records the latest layout: the container's and the fixed pane's main-axis sizes in px. */
    fun onLaidOut(containerPx: Float, fixedPx: Float) {
        container = containerPx
        laidOutFixed = fixedPx
    }

    /** Starts a drag at the current layout. */
    fun start() {
        start = laidOutFixed
        total = 0f
        lastReported = laidOutFixed
    }

    /** Adds [delta] px of pointer movement; returns the new fixed size in px, or null if it did not change. */
    fun drag(delta: Float, geometry: SplitGeometry): Float? {
        total += delta
        val target = geometry.dragged(container, start, total)
        if (target == lastReported) return null
        lastReported = target
        return target
    }
}
