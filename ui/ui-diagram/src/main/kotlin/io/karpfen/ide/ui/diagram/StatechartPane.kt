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
package io.karpfen.ide.ui.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.sync.StatechartState
import io.karpfen.ide.notation.sync.StatechartTracker
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlinx.coroutines.flow.combine

/**
 * The statechart of the state machine file that [session] holds. The drawing follows the text: whenever the text or
 * its parse changes, the diagram is derived again from the text. While the text has syntax errors, the last drawing
 * stays and a note says that it is outdated.
 *
 * The drawing is read-only. It can be moved by dragging and by scrolling, and zoomed with the buttons in its corner
 * or by scrolling while Ctrl or Cmd is held. A click on a state calls [onSelect] with the place of the state's name
 * in the text, so the caller can show it in the editor.
 */
@Composable
public fun StatechartPane(session: DocumentSession, modifier: Modifier = Modifier, onSelect: (TextRange) -> Unit = {}) {
    val state by produceState(StatechartState.EMPTY, session) {
        val tracker = StatechartTracker()
        combine(session.text.snapshot, session.parse) { snapshot, outcome -> tracker.update(snapshot, outcome) }.collect { value = it }
    }
    StatechartView(state, modifier, onSelect)
}

/**
 * The view of the statechart [state]. It draws the diagram, says so when there is none, and shows the note of a stale
 * diagram over the drawing.
 */
@Composable
internal fun StatechartView(state: StatechartState, modifier: Modifier = Modifier, onSelect: (TextRange) -> Unit = {}) {
    val colors = KarpfenTheme.colors
    Box(modifier.fillMaxSize().background(colors.window).testTag(StatechartViewTestTags.VIEW)) {
        val diagram = state.diagram
        if (diagram == null || diagram.states.isEmpty()) {
            Text(
                NOTHING_TO_DRAW,
                color = colors.textMuted,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.align(Alignment.Center).padding(16.dp).testTag(StatechartViewTestTags.EMPTY),
            )
        } else {
            StatechartCanvas(diagram, onSelect)
        }
        if (state.isStale) {
            Text(
                STALE,
                color = colors.warning,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(colors.panel)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(StatechartViewTestTags.STALE),
            )
        }
    }
}

/** What the view says while the text holds no state machine that could be read. */
private const val NOTHING_TO_DRAW = "No state machine to draw yet. The diagram appears once the text can be read."

/** What the view says while the text has syntax errors. */
private const val STALE = "The text has errors. This is the diagram of the last text that could be read."
