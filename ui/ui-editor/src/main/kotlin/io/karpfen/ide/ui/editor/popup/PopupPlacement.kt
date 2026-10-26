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
 * Where an anchored popup (completion list, hover tooltip) goes inside the overlay area — the text area, in the
 * `EditorOverlayHost` coordinate space, px. Pure functions.
 *
 * Vertically the popup goes below its anchor (its top at the anchor's bottom) if it fits there, else above (its bottom
 * at the anchor's top) if it fits there, else on the side with more room; the caller limits the popup's height to
 * [maxHeight], so it always fits on that side. Horizontally it starts at the anchor's left edge, moved left as far as
 * needed to end inside the area, but never beyond the area's left edge.
 */
internal object PopupPlacement {

    /** The height available to a popup anchored at [anchor] in an area [areaHeight] px high: the larger of both sides. */
    fun maxHeight(anchor: Rect, areaHeight: Int): Int {
        val below = areaHeight - anchor.bottom.roundToInt()
        val above = anchor.top.roundToInt()
        return maxOf(below, above, 0)
    }

    /** The top-left corner of a popup of [popup] size anchored at [anchor] in an area of [area] size. */
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
 * Lays out [content] (one popup) inside the overlay area at the place [PopupPlacement] computes for [anchor]. Fills the
 * area (the Box of the `EditorOverlays.Popups` slot, which has the text area's size) without handling pointer input
 * itself, so the text area below still receives every event outside the popup. The popup is measured with the area's
 * width and [PopupPlacement.maxHeight] as limits.
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

/** The area assumed when the slot gives unbounded constraints (it should not: the slot Box fills the text area). */
private const val UNBOUNDED_AREA = 1 shl 20
