package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A segment of the [StatusBar]; clickable when [onClick] is set. Holds behaviour, so it is a plain class with identity
 * equality (not a data class).
 */
@Immutable
public class StatusBarItem(
    public val id: String,
    public val text: String,
    public val tooltip: String? = null,
    public val icon: ImageVector? = null,
    public val onClick: (() -> Unit)? = null,
)

/** The window status bar (spec §6): [left] items aligned left, [right] items aligned right. Stateless. */
@Composable
public fun StatusBar(
    left: List<StatusBarItem>,
    right: List<StatusBarItem>,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-FOUNDATION: status bar")
