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

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.RouteStatus
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.bridges.BridgeRun
import io.karpfen.ide.ui.workbench.bridges.RunBridges

/**
 * The [bridges] of a run in the summary above its log. Each bridge has one line with its file, what it is doing and
 * how many messages it handed on in each direction. A bridge that failed is in the error colour with the reason. Below
 * it, every route that dropped or failed a message has a line of its own that says how often and why. A run without
 * bridges shows nothing.
 */
@Composable
internal fun BridgeLines(bridges: RunBridges) {
    val runs by bridges.runs.collectAsState()
    if (runs.isEmpty()) return
    val colors = KarpfenTheme.colors
    Heading("Bridges")
    runs.forEachIndexed { index, run ->
        Text(
            BridgeTexts.line(run),
            color = if (run.status.state == BridgeState.FAILED) colors.error else colors.text,
            style = KarpfenTheme.typography.uiSmall,
            modifier = Modifier.testTag(RuntimeViewTestTags.bridge(index)),
        )
        (run.status.inbound + run.status.outbound).filter { it.dropped > 0 || it.failed > 0 }.forEach { route ->
            Line("    ${BridgeTexts.trouble(route)}", colors.warning)
        }
    }
}

/** The wording of the bridge lines. */
internal object BridgeTexts {

    /** The line of [run], such as `http.kbridge.json: running, 3 in, 12 out`. */
    fun line(run: BridgeRun): String {
        val status = run.status
        val state = when (status.state) {
            BridgeState.STOPPED -> "not running"
            BridgeState.STARTING -> "starting"
            BridgeState.RUNNING -> "running, ${status.inbound.sumOf { it.delivered }} in, ${status.outbound.sumOf { it.delivered }} out"
            BridgeState.FAILED -> "failed. ${status.problem ?: "The reason is not known."}"
        }
        return "${run.name}: $state"
    }

    /** What went wrong on [route], such as `/dim to switch/dim: 2 failed (The message is not JSON.)`. */
    fun trouble(route: RouteStatus): String {
        val counts = listOfNotNull(
            "${route.dropped} dropped".takeIf { route.dropped > 0 },
            "${route.failed} failed".takeIf { route.failed > 0 },
        ).joinToString(", ")
        return "${route.label}: $counts" + (route.lastError?.let { " ($it)" } ?: "")
    }
}
