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
 * The content of the status bar. It shows `Ln x, Col y`, the selection length, the language, the line separator,
 * `UTF-8`, the error and warning counts and "Analyzing…". It also shows the runtime's status, and a click on it calls
 * [onShowRuntime]. And it shows the indicator of unseen problems of the language tools, and a click on it calls
 * [onShowOutput]. The texts come from [StatusBarSegments]. The id of each item is its segment's id, which also gives
 * its test tag `StatusBarTestTags.item(id)`.
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
 * The status bar item of [segment]. The error and warning counts and the language tools' indicator carry their
 * severity icon. The indicator is a button that calls [onShowOutput]. The runtime's status is a button that calls
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
