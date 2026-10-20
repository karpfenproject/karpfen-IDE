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

    fun hasBoundedMain(constraints: Constraints): Boolean =
        if (horizontal) constraints.hasBoundedWidth else constraints.hasBoundedHeight

    fun mainMax(constraints: Constraints): Int = if (horizontal) constraints.maxWidth else constraints.maxHeight

    fun crossMin(constraints: Constraints): Int = if (horizontal) constraints.minHeight else constraints.minWidth

    fun crossMax(constraints: Constraints): Int = if (horizontal) constraints.maxHeight else constraints.maxWidth

    fun cross(placeable: Placeable): Int = if (horizontal) placeable.height else placeable.width

    /** Constraints with an exact [main] size and the cross range [crossMin]..[crossMax]. */
    fun constraints(main: Int, crossMin: Int, crossMax: Int): Constraints =
        if (horizontal) {
            Constraints(minWidth = main, maxWidth = main, minHeight = crossMin, maxHeight = crossMax)
        } else {
            Constraints(minWidth = crossMin, maxWidth = crossMax, minHeight = main, maxHeight = main)
        }

    fun layout(scope: MeasureScope, main: Int, cross: Int, placement: Placeable.PlacementScope.() -> Unit): MeasureResult =
        if (horizontal) scope.layout(main, cross, placementBlock = placement) else scope.layout(cross, main, placementBlock = placement)

    fun place(scope: Placeable.PlacementScope, placeable: Placeable, mainOffset: Int) {
        with(scope) { if (horizontal) placeable.place(mainOffset, 0) else placeable.place(0, mainOffset) }
    }
}
