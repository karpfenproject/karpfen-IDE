package io.karpfen.ide.ui.foundation.widgets

/**
 * Minimal scrolling to reveal an item (pure; shared by [TreeView] and [TabStrip]): the smallest change of the scroll
 * position that makes the item fully visible, aligning it with the nearer viewport edge. An item larger than the
 * viewport is aligned with the viewport's start.
 */
internal object ScrollReveal {

    /**
     * The new scroll position (the content offset at the viewport's start) that reveals the item spanning
     * [itemStart] until [itemEnd] (exclusive) in a viewport of [viewportSize] currently starting at [viewportStart],
     * or null if the item is fully visible already.
     */
    fun target(itemStart: Int, itemEnd: Int, viewportStart: Int, viewportSize: Int): Int? {
        val viewportEnd = viewportStart + viewportSize
        return when {
            itemStart < viewportStart -> itemStart
            itemEnd > viewportEnd -> if (itemEnd - itemStart >= viewportSize) itemStart else itemEnd - viewportSize
            else -> null
        }
    }
}
