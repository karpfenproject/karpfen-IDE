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

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeState
import io.karpfen.ide.bridges.api.BridgeStatus
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * One bridge of a run, as the Log pane shows it.
 *
 * @property file the bridge file.
 * @property name the file relative to the project folder, which names the bridge to the user.
 * @property status what the bridge is doing and how its routes are doing. A bridge that could not be built is
 *   [BridgeState.FAILED] with the reason as its problem.
 */
public data class BridgeRun(public val file: Path, public val name: String, public val status: BridgeStatus)

/**
 * The bridges of one run. They share the session of the run through a [SessionModelLink].
 *
 * The bridges start together when the environment of the session runs, since no event can be delivered before. They
 * stop when the environment stops, when the session fails or is closed, and on [close]. A bridge that cannot start
 * does not keep the others from starting, and the run goes on without it.
 *
 * Everything works in a child scope of [parent] on the UI thread. The child scope has a supervisor job, so a failure
 * in one bridge does not cancel the others.
 *
 * @param plans the bridge files the user ticked for the run.
 * @param session the session of the run.
 * @param loader builds the bridge of a plan.
 */
public class RunBridges internal constructor(
    plans: List<BridgePlan>,
    session: RuntimeSession,
    private val parent: CoroutineScope,
    loader: BridgeLoader,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val link = SessionModelLink(session)
    private val entries: List<Entry> = plans.map { plan ->
        val bridge = plan.config?.let { loader.bridge(it, link, scope) }
        Entry(plan, bridge, bridge?.status ?: MutableStateFlow(BridgeStatus(BridgeState.FAILED, plan.problem)))
    }
    private var started = false

    /** The bridges of the run in the order of their names, each with its current status. */
    public val runs: StateFlow<List<BridgeRun>> =
        if (entries.isEmpty()) {
            MutableStateFlow(emptyList())
        } else {
            combine(entries.map { it.status }) { statuses -> entries.mapIndexed { index, entry -> entry.run(statuses[index]) } }
                .stateIn(scope, SharingStarted.Eagerly, entries.map { it.run(it.status.value) })
        }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { state ->
                when (state) {
                    is SessionState.Running -> if (!started) {
                        started = true
                        entries.forEach { entry -> entry.bridge?.let { launch { it.start() } } }
                    }
                    is SessionState.Stopping, is SessionState.Stopped, is SessionState.Failed, SessionState.Closed -> if (started) {
                        started = false
                        stopAll()
                    }
                    SessionState.Deploying, is SessionState.Ready, is SessionState.Halted -> Unit
                }
            }
        }
    }

    /**
     * Stops the bridges and stops following the session. The bridges let go of their ports and connections even if
     * [parent] is cancelled already, as it is when the workspace closes.
     */
    internal fun close() {
        scope.cancel()
        parent.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { stopAll() } }
    }

    private suspend fun stopAll() {
        entries.forEach { it.bridge?.stop() }
    }

    /** A plan with its [bridge], which is null if it could not be built, and the [status] to show for it. */
    private class Entry(val plan: BridgePlan, val bridge: Bridge?, val status: StateFlow<BridgeStatus>) {
        fun run(status: BridgeStatus): BridgeRun = BridgeRun(plan.file, plan.name, status)
    }
}
