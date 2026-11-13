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
package io.karpfen.ide.ui.workbench.runtime.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts

/**
 * The debugger's line of the selected run, which shows [state]. While the run runs, it holds the Pause button, which
 * calls [onPause]. While the run is paused, it says where in plain words and holds the Resume and Step buttons, which
 * call [onResume] and [onStep]. While the debugger is unavailable, the line is empty. The composable only renders the
 * state and calls back.
 */
@Composable
internal fun DebuggerBar(state: DebuggerState, onPause: () -> Unit, onResume: () -> Unit, onStep: () -> Unit) {
    val colors = KarpfenTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(RuntimeViewTestTags.DEBUGGER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Debugger", color = colors.text, style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
        when (state) {
            DebuggerState.Unavailable -> Unit
            DebuggerState.Running -> {
                Text("Running", color = colors.textMuted, style = KarpfenTheme.typography.uiSmall, modifier = Modifier.weight(1f))
                SmallButton("Pause", RuntimeViewTestTags.PAUSE, onClick = onPause)
            }
            is DebuggerState.Paused -> {
                Text(
                    RuntimeTexts.pause(state.place),
                    color = colors.warning,
                    style = KarpfenTheme.typography.uiSmall,
                    modifier = Modifier.weight(1f).testTag(RuntimeViewTestTags.PAUSE_PLACE),
                )
                SmallButton("Resume", RuntimeViewTestTags.RESUME, onClick = onResume)
                SmallButton("Step", RuntimeViewTestTags.STEP, onClick = onStep)
            }
        }
    }
}
