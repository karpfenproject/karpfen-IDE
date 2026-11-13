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
package io.karpfen.ide.ui.workbench.bridges

import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.RouteStatus

/**
 * The wording of the bridges of a run: one line per bridge and one per route that dropped or failed a message. The
 * Log pane of the window and the headless mode both say it this way. The object is pure and holds no state.
 */
public object BridgeTexts {

    /** The line of [run], such as `http.kbridge.json: running, 3 in, 12 out`. */
    public fun line(run: BridgeRun): String {
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
    public fun trouble(route: RouteStatus): String {
        val counts = listOfNotNull(
            "${route.dropped} dropped".takeIf { route.dropped > 0 },
            "${route.failed} failed".takeIf { route.failed > 0 },
        ).joinToString(", ")
        return "${route.label}: $counts" + (route.lastError?.let { " ($it)" } ?: "")
    }
}
