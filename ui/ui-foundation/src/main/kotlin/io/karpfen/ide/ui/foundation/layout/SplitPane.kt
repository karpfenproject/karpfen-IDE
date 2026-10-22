package io.karpfen.ide.ui.foundation.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlin.math.roundToInt

/** Direction in which a [SplitPane] places its two children. */
public enum class SplitOrientation {
    /** Side by side; the divider is vertical. */
    HORIZONTAL,

    /** Stacked; the divider is horizontal. */
    VERTICAL,
}

/** The pane of a [SplitPane] whose size is hoisted; the other pane takes the remaining space. */
public enum class SplitSide {
    /** The left (HORIZONTAL) or top (VERTICAL) pane. */
    FIRST,

    /** The right (HORIZONTAL) or bottom (VERTICAL) pane. */
    SECOND,
}

/** Test tags of [SplitPane] (for headless UI tests of the foundation and its users). */
public object SplitPaneTestTags {
    /** The draggable divider of every [SplitPane]. */
    public const val DIVIDER: String = "SplitPane.divider"
}

/**
 * Two panes with a draggable divider. Stateless and size-based: the [fixedSide] pane has the hoisted size [fixedSize] —
 * its width for [SplitOrientation.HORIZONTAL], its height for [SplitOrientation.VERTICAL] — and the other pane takes
 * the rest, so an IDE tool window keeps its size when the window is resized. Every drag frame reports the new size
 * through [onFixedSizeChange], clamped so that neither pane shrinks below [minFirst] / [minSecond]; when the container
 * is too small for [fixedSize], the fixed pane is laid out smaller (down to its minimum) without reporting a change, so
 * the caller's size is restored when the container grows again. The size is in dp, like the tool-window sizes the
 * workbench persists, so callers convert nothing (a hoisted fraction would need a conversion in every caller).
 *
 * The divider is a 1 dp line in the palette's border colour between the panes; its drag handle is a few dp wider and
 * follows the pointer from the press on (no touch slop). On an unbounded main axis the split takes the fixed pane's
 * size plus the other pane's minimum. Needs [KarpfenTheme].
 */
@Composable
public fun SplitPane(
    orientation: SplitOrientation,
    fixedSide: SplitSide,
    fixedSize: Dp,
    onFixedSizeChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    minFirst: Dp = 80.dp,
    minSecond: Dp = 80.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val geometry = with(density) {
        SplitGeometry(fixedSide, minFirst.toPx(), minSecond.toPx(), SplitPaneDefaults.DividerThickness.toPx())
    }
    val fixedPx = with(density) { fixedSize.toPx() }
    val handlePx = with(density) { SplitPaneDefaults.HandleWidth.roundToPx() }
    val tracker = remember { SplitDragTracker() }
    val currentGeometry = rememberUpdatedState(geometry)
    val currentOnChange = rememberUpdatedState(onFixedSizeChange)

    Layout(
        contents = listOf(
            first,
            second,
            {
                SplitDivider(
                    orientation = orientation,
                    onDragStart = tracker::start,
                    onDrag = { delta ->
                        tracker.drag(delta, currentGeometry.value)?.let { px ->
                            currentOnChange.value(with(density) { px.toDp() })
                        }
                    },
                )
            },
        ),
        modifier = modifier,
    ) { (firstPane, secondPane, divider), constraints ->
        measureSplit(
            axis = SplitAxis(orientation),
            constraints = constraints,
            geometry = geometry,
            fixedPx = fixedPx,
            handlePx = handlePx,
            tracker = tracker,
            measurables = SplitMeasurables(firstPane.single(), secondPane.single(), divider.single()),
        )
    }
}

/** A tool window: a header with [title] and optional [actions], above [content]. Needs [KarpfenTheme]. */
@Composable
public fun ToolWindow(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = KarpfenTheme.colors
    Column(modifier.background(colors.panel)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ToolWindowDefaults.HeaderHeight).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = KarpfenTheme.typography.uiSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/** Fixed measures of [SplitPane]. */
internal object SplitPaneDefaults {
    /** Space the divider line takes between the panes. */
    val DividerThickness: Dp = 1.dp

    /** Width of the divider's drag handle, centred on the line (it overlaps the panes' edges). */
    val HandleWidth: Dp = 6.dp
}

/** Fixed measures of [ToolWindow]. */
internal object ToolWindowDefaults {
    /** Height of the header row. */
    val HeaderHeight: Dp = 28.dp
}

/** The three children of a [SplitPane] layout: both panes and the divider's drag handle. */
private class SplitMeasurables(val first: Measurable, val second: Measurable, val divider: Measurable)

/**
 * Measures and places the children of a [SplitPane]: the pane sizes come from [geometry] for the requested [fixedPx]
 * (in a container of the natural size when the main axis is unbounded) and are reported to [tracker], so a drag starts
 * from what is shown. The cross size is the largest allowed, or the larger pane's when the cross axis is unbounded. The
 * drag handle of [handlePx] is centred on the divider line and overlaps the panes' edges.
 */
private fun MeasureScope.measureSplit(
    axis: SplitAxis,
    constraints: Constraints,
    geometry: SplitGeometry,
    fixedPx: Float,
    handlePx: Int,
    tracker: SplitDragTracker,
    measurables: SplitMeasurables,
): MeasureResult {
    val container = if (axis.hasBoundedMain(constraints)) {
        axis.mainMax(constraints).toFloat()
    } else {
        geometry.naturalContainer(fixedPx)
    }
    val sizes = geometry.layout(container, fixedPx)
    tracker.onLaidOut(container, if (geometry.fixedSide == SplitSide.FIRST) sizes.first else sizes.second)

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
