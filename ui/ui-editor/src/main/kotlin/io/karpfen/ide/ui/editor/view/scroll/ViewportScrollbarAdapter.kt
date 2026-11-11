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
package io.karpfen.ide.ui.editor.view.scroll

import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.State
import io.karpfen.ide.ui.editor.state.EditorViewport
import io.karpfen.ide.ui.editor.state.ViewportSnapshot

/** The axis a [ViewportScrollbarAdapter] drives. */
internal enum class ScrollAxis { HORIZONTAL, VERTICAL }

/**
 * Drives a Compose desktop scrollbar from an [EditorViewport]. The editor has its own viewport, so it needs a custom
 * adapter. The adapter reads the scroll offset and the sizes of one [axis] from [snapshot]. [snapshot] is the state of
 * the viewport as Compose state, so the scrollbar recomposes when it changes. Scrolling goes through the viewport,
 * which clamps the offsets.
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
