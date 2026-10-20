package io.karpfen.ide.ui.editor.view.scroll

import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.State
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.state.ViewportSnapshot

/** The axis a [ViewportScrollbarAdapter] drives. */
internal enum class ScrollAxis { HORIZONTAL, VERTICAL }

/**
 * Drives a Compose desktop scrollbar from an [EditorViewport] (spec §7.2: scrollbars with custom adapters): reads the
 * scroll offset and sizes of one [axis] from [snapshot] (the viewport's state as Compose state, so the scrollbar
 * recomposes when it changes) and scrolls through the viewport, which clamps.
 */
internal class ViewportScrollbarAdapter(
    private val snapshot: State<ViewportSnapshot>,
    private val viewport: EditorViewport,
    private val axis: ScrollAxis,
) : ScrollbarAdapter {

    override val scrollOffset: Double
        get() = snapshot.value.let { if (axis == ScrollAxis.VERTICAL) it.scrollY else it.scrollX }.toDouble()

    override val contentSize: Double
        get() = snapshot.value.let { if (axis == ScrollAxis.VERTICAL) it.contentHeight else it.contentWidth }.toDouble()

    override val viewportSize: Double
        get() = snapshot.value.let { if (axis == ScrollAxis.VERTICAL) it.viewportHeight else it.viewportWidth }.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        val current = viewport.state.value
        if (axis == ScrollAxis.VERTICAL) {
            viewport.scrollTo(current.scrollX, scrollOffset.toFloat())
        } else {
            viewport.scrollTo(scrollOffset.toFloat(), current.scrollY)
        }
    }
}
