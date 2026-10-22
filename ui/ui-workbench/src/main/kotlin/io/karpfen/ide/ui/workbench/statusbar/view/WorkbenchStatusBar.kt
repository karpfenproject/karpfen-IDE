package io.karpfen.ide.ui.workbench.statusbar.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.StatusBar
import io.karpfen.ide.ui.foundation.widgets.StatusBarItem
import io.karpfen.ide.ui.workbench.statusbar.StatusBarSegments
import io.karpfen.ide.ui.workbench.statusbar.StatusBarUiState
import io.karpfen.ide.ui.workbench.statusbar.StatusSegment
import io.karpfen.ide.ui.workbench.statusbar.StatusSegmentKind

/**
 * The status bar content: `Ln x, Col y`, selection length, language, line separator, `UTF-8`, error and warning counts,
 * "Analyzing…". The texts come from [StatusBarSegments]; each item's id is its segment's id
 * (`StatusBarTestTags.item(id)`).
 */
@Composable
internal fun WorkbenchStatusBar(state: StatusBarUiState, modifier: Modifier = Modifier) {
    StatusBar(
        left = StatusBarSegments.left(state).map(::item),
        right = StatusBarSegments.right(state).map(::item),
        modifier = modifier,
    )
}

/** The status-bar item of [segment]; the error and warning counts carry their severity icon. */
private fun item(segment: StatusSegment): StatusBarItem = StatusBarItem(
    id = segment.id,
    text = segment.text,
    tooltip = segment.tooltip,
    icon = when (segment.kind) {
        StatusSegmentKind.ERRORS -> KarpfenIcons.Error
        StatusSegmentKind.WARNINGS -> KarpfenIcons.Warning
        else -> null
    },
)
