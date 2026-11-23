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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Test tags of [ShareSplitPane], for headless UI tests of the foundation and of the modules that use it. */
public object ShareSplitPaneTestTags {
    /** The draggable divider of every [ShareSplitPane]. */
    public const val DIVIDER: String = "ShareSplitPane.divider"
}

/**
 * Two parts with a draggable divider, sized by a share of the space. The split is stateless. The [first] part takes
 * the hoisted [share] of the space next to the divider, its width in a [SplitOrientation.HORIZONTAL] split and its
 * height in a [SplitOrientation.VERTICAL] one, and the [second] part takes the rest. So both parts grow and shrink
 * together when the container is resized, as the panes of a split editor do. The [SplitPane] is the sibling for a part
 * of a fixed size, such as a tool window.
 *
 * Every drag frame reports the new share through [onShareChange]. The share shown and every share reported are kept
 * between [minShare] and [maxShare], so a drag can never make a part vanish.
 *
 * The divider looks and behaves like the divider of the [SplitPane]: a 1 dp line in the palette's border colour with a
 * wider drag handle that follows the pointer from the press on. Its test tag is [ShareSplitPaneTestTags.DIVIDER]. On
 * an unbounded main axis the split takes a size of 400 dp and divides that. The split needs `KarpfenTheme`.
 */
@Composable
public fun ShareSplitPane(
    orientation: SplitOrientation,
    share: Float,
    onShareChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minShare: Float = 0.05f,
    maxShare: Float = 0.95f,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val geometry = ShareSplitGeometry(minShare, maxShare, with(density) { SplitPaneDefaults.DividerThickness.toPx() })
    val handlePx = with(density) { SplitPaneDefaults.HandleWidth.roundToPx() }
    val unboundedPx = with(density) { SplitPaneDefaults.UnboundedShareSize.toPx() }
    val tracker = remember { ShareDragTracker() }
    val currentGeometry = rememberUpdatedState(geometry)
    val currentOnChange = rememberUpdatedState(onShareChange)

    Layout(
        contents = listOf(
            first,
            second,
            {
                SplitDivider(
                    orientation = orientation,
                    onDragStart = tracker::start,
                    onDrag = { delta -> tracker.drag(delta, currentGeometry.value)?.let { currentOnChange.value(it) } },
                    tag = ShareSplitPaneTestTags.DIVIDER,
                )
            },
        ),
        modifier = modifier,
    ) { (firstPart, secondPart, divider), constraints ->
        measureShares(
            axis = SplitAxis(orientation),
            constraints = constraints,
            geometry = geometry,
            share = geometry.bounded(share),
            unboundedPx = unboundedPx,
            handlePx = handlePx,
            tracker = tracker,
            measurables = SplitMeasurables(firstPart.single(), secondPart.single(), divider.single()),
        )
    }
}

/**
 * Measures and places the children of a [ShareSplitPane]. The part sizes come from [geometry] for [share], and the
 * layout is reported to [tracker], so a drag starts from what is shown. The cross size is the largest allowed size,
 * or the larger part's size when the cross axis is unbounded. The drag handle of [handlePx] is centred on the divider
 * line and overlaps the parts' edges.
 */
private fun MeasureScope.measureShares(
    axis: SplitAxis,
    constraints: Constraints,
    geometry: ShareSplitGeometry,
    share: Float,
    unboundedPx: Float,
    handlePx: Int,
    tracker: ShareDragTracker,
    measurables: SplitMeasurables,
): MeasureResult {
    val container = if (axis.hasBoundedMain(constraints)) axis.mainMax(constraints).toFloat() else unboundedPx
    val sizes = geometry.layout(container, share)
    tracker.onLaidOut(container, sizes.first, share)

    val containerPx = container.roundToInt()
    val firstPx = sizes.first.roundToInt()
    val dividerPx = geometry.divider.roundToInt()
    val secondPx = (containerPx - firstPx - dividerPx).coerceAtLeast(0)
    val crossMin = axis.crossMin(constraints)
    val crossMax = axis.crossMax(constraints)
    val firstPlaceable = measurables.first.measure(axis.constraints(firstPx, crossMin, crossMax))
    val secondPlaceable = measurables.second.measure(axis.constraints(secondPx, crossMin, crossMax))
    val cross = if (crossMax != Constraints.Infinity) {
        crossMax
    } else {
        maxOf(axis.cross(firstPlaceable), axis.cross(secondPlaceable), crossMin)
    }
    val dividerPlaceable = measurables.divider.measure(axis.constraints(handlePx, cross, cross))
    val handleOffset = firstPx + dividerPx / 2 - handlePx / 2

    return axis.layout(this, containerPx, cross) {
        axis.place(this, firstPlaceable, 0)
        axis.place(this, secondPlaceable, firstPx + dividerPx)
        axis.place(this, dividerPlaceable, handleOffset)
    }
}
