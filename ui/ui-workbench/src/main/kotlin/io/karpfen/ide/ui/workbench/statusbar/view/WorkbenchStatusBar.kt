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
package io.karpfen.ide.ui.workbench.statusbar.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.StatusBar
import io.karpfen.ide.ui.foundation.widgets.StatusBarItem
import io.karpfen.ide.ui.workbench.settings.ZoomStep
import io.karpfen.ide.ui.workbench.statusbar.StatusBarSegments
import io.karpfen.ide.ui.workbench.statusbar.StatusBarUiState
import io.karpfen.ide.ui.workbench.statusbar.StatusSegment
import io.karpfen.ide.ui.workbench.statusbar.StatusSegmentKind

/** What the status bar calls when one of its buttons is clicked. */
internal class StatusBarActions(
    /** Shows or hides the Problems tool window. */
    val onToggleProblems: () -> Unit,
    /** Shows the Language Tools Output. */
    val onShowOutput: () -> Unit,
    /** Shows the Runtime tool window. */
    val onShowRuntime: () -> Unit,
    /** Changes the scale of the whole workbench. */
    val onZoomUi: (ZoomStep) -> Unit,
)

/**
 * The content of the status bar. It starts with the label of the Problems tool window, which shows or hides that tool
 * window. It shows `Ln x, Col y`, the selection length, the language, the line separator, `UTF-8`, the error and
 * warning counts and "Analyzing…". It also shows the runtime's status, which shows the Runtime tool window, and the
 * indicator of unseen problems of the language tools, which shows their output. At the right end are the zoom controls
 * for the scale [uiScale] of the workbench.
 *
 * The texts come from [StatusBarSegments]. The id of each item is its segment's id, which also gives its test tag
 * `StatusBarTestTags.item(id)`. The clicks go to [actions].
 */
@Composable
internal fun WorkbenchStatusBar(
    state: StatusBarUiState,
    uiScale: Float,
    actions: StatusBarActions,
    modifier: Modifier = Modifier,
) {
    StatusBar(
        left = StatusBarSegments.left(state).map { item(it, actions) },
        right = (StatusBarSegments.right(state) + StatusBarSegments.zoom(uiScale)).map { item(it, actions) },
        modifier = modifier,
    )
}

/**
 * The status bar item of [segment]. The error and warning counts, the Problems label and the language tools' indicator
 * carry their severity icon. The segments that are buttons call the matching one of [actions]. The Problems label is
 * a button only while there are problems. Without any it is greyed out.
 */
private fun item(segment: StatusSegment, actions: StatusBarActions): StatusBarItem = StatusBarItem(
    id = segment.id,
    text = segment.text,
    tooltip = segment.tooltip,
    icon = when (segment.kind) {
        StatusSegmentKind.ERRORS, StatusSegmentKind.LANGUAGE_TOOLS_ERRORS, StatusSegmentKind.PROBLEMS_ERRORS -> KarpfenIcons.Error
        StatusSegmentKind.WARNINGS, StatusSegmentKind.LANGUAGE_TOOLS_WARNINGS, StatusSegmentKind.PROBLEMS_WARNINGS ->
            KarpfenIcons.Warning
        else -> null
    },
    onClick = when (segment.kind) {
        StatusSegmentKind.PROBLEMS_ERRORS, StatusSegmentKind.PROBLEMS_WARNINGS -> actions.onToggleProblems
        StatusSegmentKind.LANGUAGE_TOOLS_ERRORS, StatusSegmentKind.LANGUAGE_TOOLS_WARNINGS -> actions.onShowOutput
        StatusSegmentKind.RUNTIME -> actions.onShowRuntime
        StatusSegmentKind.UI_ZOOM_OUT -> ({ actions.onZoomUi(ZoomStep.OUT) })
        StatusSegmentKind.UI_ZOOM -> ({ actions.onZoomUi(ZoomStep.RESET) })
        StatusSegmentKind.UI_ZOOM_IN -> ({ actions.onZoomUi(ZoomStep.IN) })
        else -> null
    },
    isMuted = segment.kind == StatusSegmentKind.PROBLEMS,
)

/**
 * The bar below the editor. It holds the zoom controls for the font size [editorFontSize] of the editors, a minus
 * button, the current size and a plus button, and [onZoom] gets the step that was clicked. The bar looks like the
 * status bar of the window and, like it, never takes the keyboard focus away from the editor. Its test tag is [tag].
 */
@Composable
internal fun EditorZoomBar(editorFontSize: Float, onZoom: (ZoomStep) -> Unit, tag: String, modifier: Modifier = Modifier) {
    val items = StatusBarSegments.editorZoom(editorFontSize).map { segment ->
        val step = when (segment.kind) {
            StatusSegmentKind.EDITOR_ZOOM_OUT -> ZoomStep.OUT
            StatusSegmentKind.EDITOR_ZOOM_IN -> ZoomStep.IN
            else -> ZoomStep.RESET
        }
        StatusBarItem(id = segment.id, text = segment.text, tooltip = segment.tooltip, onClick = { onZoom(step) })
    }
    StatusBar(left = emptyList(), right = items, modifier = modifier, tag = tag)
}
