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
package io.karpfen.ide.ui.foundation.layout

import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints

/**
 * Maps the main axis (along which the panes are placed) and the cross axis of a [SplitPane] onto x and y, so the
 * measure code is written once for both orientations.
 */
internal class SplitAxis(orientation: SplitOrientation) {
    private val horizontal = orientation == SplitOrientation.HORIZONTAL

    /** Whether [constraints] bound the main axis (an unbounded one makes the split take its natural size). */
    fun hasBoundedMain(constraints: Constraints): Boolean =
        if (horizontal) constraints.hasBoundedWidth else constraints.hasBoundedHeight

    /** The largest main-axis size [constraints] allow. */
    fun mainMax(constraints: Constraints): Int = if (horizontal) constraints.maxWidth else constraints.maxHeight

    /** The smallest cross-axis size [constraints] allow. */
    fun crossMin(constraints: Constraints): Int = if (horizontal) constraints.minHeight else constraints.minWidth

    /** The largest cross-axis size [constraints] allow. */
    fun crossMax(constraints: Constraints): Int = if (horizontal) constraints.maxHeight else constraints.maxWidth

    /** The measured cross-axis size of [placeable]. */
    fun cross(placeable: Placeable): Int = if (horizontal) placeable.height else placeable.width

    /** Constraints with an exact [main] size and the cross range [crossMin]..[crossMax]. */
    fun constraints(main: Int, crossMin: Int, crossMax: Int): Constraints =
        if (horizontal) {
            Constraints(minWidth = main, maxWidth = main, minHeight = crossMin, maxHeight = crossMax)
        } else {
            Constraints(minWidth = crossMin, maxWidth = crossMax, minHeight = main, maxHeight = main)
        }

    /** The layout of [scope] with a [main] by [cross] size, placing the children with [placement]. */
    fun layout(scope: MeasureScope, main: Int, cross: Int, placement: Placeable.PlacementScope.() -> Unit): MeasureResult =
        if (horizontal) scope.layout(main, cross, placementBlock = placement) else scope.layout(cross, main, placementBlock = placement)

    /** Places [placeable] at [mainOffset] along the main axis and at the start of the cross axis. */
    fun place(scope: Placeable.PlacementScope, placeable: Placeable, mainOffset: Int) {
        with(scope) { if (horizontal) placeable.place(mainOffset, 0) else placeable.place(0, mainOffset) }
    }
}
