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
import io.karpfen.ide.ui.workbench.statusbar.StatusBarSegments
import io.karpfen.ide.ui.workbench.statusbar.StatusBarUiState
import io.karpfen.ide.ui.workbench.statusbar.StatusSegment
import io.karpfen.ide.ui.workbench.statusbar.StatusSegmentKind

/**
 * The status bar content: `Ln x, Col y`, selection length, language, line separator, `UTF-8`, error and warning counts,
 * "Analyzing…", the runtime's status, whose click calls [onShowRuntime], and the indicator of unseen problems of the
 * language tools, whose click calls [onShowOutput]. The texts come from [StatusBarSegments]; each item's id is its
 * segment's id (`StatusBarTestTags.item(id)`).
 */
@Composable
internal fun WorkbenchStatusBar(
    state: StatusBarUiState,
    onShowOutput: () -> Unit,
    onShowRuntime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusBar(
        left = StatusBarSegments.left(state).map { item(it, onShowOutput, onShowRuntime) },
        right = StatusBarSegments.right(state).map { item(it, onShowOutput, onShowRuntime) },
        modifier = modifier,
    )
}

/**
 * The status-bar item of [segment]; the error and warning counts and the language tools' indicator carry their
 * severity icon, the indicator is a button that calls [onShowOutput] and the runtime's status one that calls
 * [onShowRuntime].
 */
private fun item(segment: StatusSegment, onShowOutput: () -> Unit, onShowRuntime: () -> Unit): StatusBarItem = StatusBarItem(
    id = segment.id,
    text = segment.text,
    tooltip = segment.tooltip,
    icon = when (segment.kind) {
        StatusSegmentKind.ERRORS, StatusSegmentKind.LANGUAGE_TOOLS_ERRORS -> KarpfenIcons.Error
        StatusSegmentKind.WARNINGS, StatusSegmentKind.LANGUAGE_TOOLS_WARNINGS -> KarpfenIcons.Warning
        else -> null
    },
    onClick = when (segment.kind) {
        StatusSegmentKind.LANGUAGE_TOOLS_ERRORS, StatusSegmentKind.LANGUAGE_TOOLS_WARNINGS -> onShowOutput
        StatusSegmentKind.RUNTIME -> onShowRuntime
        else -> null
    },
)
