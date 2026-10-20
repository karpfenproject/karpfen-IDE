package io.karpfen.ide.ui.foundation.layout

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/**
 * Two panes with a draggable divider (spec §6). Stateless, size-based (decision 46 of the work-package plan; the spec
 * sketch hoisted a fraction): the [fixedSide] pane has the hoisted size [fixedSize] — its width for
 * [SplitOrientation.HORIZONTAL], its height for [SplitOrientation.VERTICAL] — and the other pane takes the rest, so an
 * IDE tool window keeps its size when the window is resized. Every drag frame reports the new size through
 * [onFixedSizeChange], clamped so that neither pane shrinks below [minFirst] / [minSecond]; when the container is too
 * small for [fixedSize], the fixed pane is laid out smaller (down to its minimum) without reporting a change, so the
 * caller's size is restored when the container grows again. The composable needs no conversion by its caller.
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
): Unit = TODO("WP-FOUNDATION: size-based split pane with draggable divider")

/** A tool window (spec §6): a header with [title] and optional [actions], above [content]. */
@Composable
public fun ToolWindow(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
): Unit = TODO("WP-FOUNDATION: tool window")
