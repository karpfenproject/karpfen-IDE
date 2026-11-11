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
package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/**
 * Computes where an anchored popup goes inside the overlay area. Anchored popups are the completion list and the hover
 * tooltip. The overlay area is the text area, in pixels of the `EditorOverlayHost` coordinate space. The object holds
 * pure functions.
 *
 * Vertically, the popup goes below its anchor if it fits there, with its top at the anchor's bottom. Otherwise it goes
 * above the anchor if it fits there, with its bottom at the anchor's top. If it fits on neither side, it goes on the
 * side with more room. The caller limits the popup's height to [maxHeight], so it always fits on that side.
 * Horizontally, it starts at the anchor's left edge and moves left as far as needed to end inside the area. It never
 * moves beyond the left edge of the area.
 */
internal object PopupPlacement {

    /**
     * Returns the height available to a popup anchored at [anchor] in an area that is [areaHeight] pixels high. That is
     * the larger of the room below and the room above the anchor.
     */
    fun maxHeight(anchor: Rect, areaHeight: Int): Int {
        val below = areaHeight - anchor.bottom.roundToInt()
        val above = anchor.top.roundToInt()
        return maxOf(below, above, 0)
    }

    /** Returns the top left corner of a popup of size [popup], anchored at [anchor] in an area of size [area]. */
    fun topLeft(anchor: Rect, popup: IntSize, area: IntSize): IntOffset {
        val below = anchor.bottom.roundToInt()
        val roomBelow = area.height - below
        val roomAbove = anchor.top.roundToInt()
        val y = when {
            popup.height <= roomBelow -> below
            popup.height <= roomAbove -> roomAbove - popup.height
            roomBelow >= roomAbove -> below
            else -> maxOf(roomAbove - popup.height, 0)
        }
        val x = anchor.left.roundToInt().coerceAtMost(area.width - popup.width).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * Lays out [content], which is one popup, inside the overlay area at the place [PopupPlacement] computes for [anchor].
 * The layout fills the area, which is the Box of the `EditorOverlays.Popups` slot and has the size of the text area.
 * It does not handle pointer input itself, so the text area below still receives every event outside the popup. The
 * popup is measured with the width of the area and [PopupPlacement.maxHeight] as limits.
 */
@Composable
internal fun AnchoredPopupLayout(anchor: Rect, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val area = IntSize(
            if (constraints.hasBoundedWidth) constraints.maxWidth else UNBOUNDED_AREA,
            if (constraints.hasBoundedHeight) constraints.maxHeight else UNBOUNDED_AREA,
        )
        val limits = Constraints(maxWidth = area.width, maxHeight = PopupPlacement.maxHeight(anchor, area.height))
        val placeables = measurables.map { it.measure(limits) }
        layout(constraints.constrainWidth(area.width), constraints.constrainHeight(area.height)) {
            placeables.forEach { it.place(PopupPlacement.topLeft(anchor, IntSize(it.width, it.height), area)) }
        }
    }
}

/**
 * The area size assumed when the slot gives unbounded constraints. That should not happen, because the slot Box fills
 * the text area.
 */
private const val UNBOUNDED_AREA = 1 shl 20
