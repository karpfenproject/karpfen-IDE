package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** One tab of a [TabStrip]. [id] is stable across recompositions (e.g. the document path). */
@Immutable
public data class TabItem(
    public val id: String,
    public val title: String,
    public val tooltip: String? = null,
    public val isDirty: Boolean = false,
    public val icon: ImageVector? = null,
)

/**
 * A horizontal strip of tabs (spec §6): title, tooltip, dirty dot, close button, middle-click closes, horizontal
 * scrolling when the tabs overflow; the selected tab is scrolled into view. Stateless.
 */
@Composable
public fun TabStrip(
    tabs: List<TabItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-FOUNDATION: tab strip")
