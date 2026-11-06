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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.foundation.keys.displayText
import io.karpfen.ide.ui.workbench.runtime.RunSupport
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The [RuntimeState] of the opened workspace: created when a workspace opens, in a scope of its own below [scope], and
 * shut down when it closes or the IDE exits. Shutting down ends every session and closes the host within
 * [shutdownBound]; whatever is left then is closed at once. The bound holds also while a host blocks a thread to stop
 * its container: the shutdown runs in the runtime state's own scope, and the caller waits for it no longer than the
 * bound.
 */
internal class RuntimeHolder(
    private val scope: CoroutineScope,
    private val services: WorkbenchServices,
    private val support: RunSupport,
    private val bindings: SubStateBindings,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val shutdownBound: Duration = SHUTDOWN_BOUND,
) {
    private val current = MutableStateFlow<Opened?>(null)
    private val runtime = MutableStateFlow<RuntimeState?>(null)

    /** The runtime state of the opened workspace, or null. */
    val state: StateFlow<RuntimeState?> get() = runtime.asStateFlow()

    /** Creates the runtime state of [workspace], replacing none: the previous one was closed with its workspace. */
    fun open(workspace: Workspace) {
        val own = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val state = RuntimeState(
            own, workspace, services.runtimeSessions, services.runtimeHosts, services.settings, services.runtimeClients, support,
            services.ioDispatcher, clock, RuntimeCommands.RUN_SHORTCUT.displayText(services.platform),
        )
        current.value = Opened(state, own)
        runtime.value = state
        bindings.bindRuntime(state)
    }

    /** Shuts the runtime state down within the bound, then closes it. */
    suspend fun shutdown() {
        val opened = current.value ?: return
        val shutdown = opened.scope.async { opened.state.shutdown() }
        try {
            withTimeoutOrNull(shutdownBound) { shutdown.await() }
        } finally {
            close()
        }
    }

    /** Closes the runtime state at once. */
    fun close() {
        val opened = current.value ?: return
        current.value = null
        runtime.value = null
        bindings.bindRuntime(null)
        opened.state.close()
        opened.scope.cancel()
    }

    /** A runtime state with the scope it runs in. */
    private class Opened(val state: RuntimeState, val scope: CoroutineScope)

    companion object {
        /** How long shutting down the runtime of a workspace may take. */
        val SHUTDOWN_BOUND: Duration = 15.seconds
    }
}
