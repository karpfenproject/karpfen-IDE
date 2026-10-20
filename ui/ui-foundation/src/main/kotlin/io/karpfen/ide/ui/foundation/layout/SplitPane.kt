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

/**
 * Two panes with a draggable divider (spec §6). Stateless: the divider position is hoisted as [fraction] (share of
 * the first pane, `0..1`) and every drag reports a new value through [onFractionChange]; the panes never shrink below
 * [minFirst] / [minSecond].
 */
@Composable
public fun SplitPane(
    orientation: SplitOrientation,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minFirst: Dp = 80.dp,
    minSecond: Dp = 80.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
): Unit = TODO("WP-FOUNDATION: split pane with draggable divider")

/** A tool window (spec §6): a header with [title] and optional [actions], above [content]. */
@Composable
public fun ToolWindow(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
): Unit = TODO("WP-FOUNDATION: tool window")
