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

import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.workbench.command.RuntimeCommandIds
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Registers the runtime commands (`RuntimeCommandIds`) over the [runtime] of the opened workspace: Run (Shift+F10),
 * Stop (Ctrl+F2, Cmd+F2 on macOS), Start runtime and Stop runtime. Run needs an opened workspace, Stop a run it can
 * stop, Start runtime a host without a runtime and Stop runtime a host that has or prepares one.
 */
internal class RuntimeCommands(private val scope: CoroutineScope, private val runtime: StateFlow<RuntimeState?>) {

    /** Registers the commands in [registry]. */
    fun register(registry: CommandRegistry) {
        val open = runtime.map { it != null }.eagerly(runtime.value != null)
        val stoppable = follow(false) { state -> state.stopTarget.map { it != null } }
        val startable = follow(false) { state -> state.host.map { it is HostStatus.Stopped || it is HostStatus.Failed } }
        val stoppableRuntime = follow(false) { state -> state.host.map { it is HostStatus.Running || it is HostStatus.Preparing } }
        registry.register(Command(RuntimeCommandIds.RUN, "Run", RUN_SHORTCUT, open) { runtime.value?.run() })
        registry.register(Command(RuntimeCommandIds.STOP, "Stop", STOP_SHORTCUT, stoppable) { runtime.value?.stopSelected() })
        registry.register(
            Command(RuntimeCommandIds.START_RUNTIME, "Start Runtime", isEnabled = startable) { runtime.value?.startRuntime() },
        )
        registry.register(
            Command(RuntimeCommandIds.STOP_RUNTIME, "Stop Runtime", isEnabled = stoppableRuntime) { runtime.value?.stopRuntime() },
        )
    }

    /** What [select] answers for the current runtime state, [none] without one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun follow(none: Boolean, select: (RuntimeState) -> Flow<Boolean>): StateFlow<Boolean> =
        runtime.flatMapLatest { state -> state?.let(select) ?: flowOf(none) }.eagerly(none)

    /** This flow as a state flow in [scope], starting with [initial]. */
    private fun <T> Flow<T>.eagerly(initial: T): StateFlow<T> = stateIn(scope, SharingStarted.Eagerly, initial)

    companion object {
        /** The shortcut of Run. */
        val RUN_SHORTCUT: KeyShortcut = KeyShortcut(KeyCode.F10, shift = true)

        /** The shortcut of Stop: Ctrl+F2, Cmd+F2 on macOS. */
        val STOP_SHORTCUT: KeyShortcut = KeyShortcut(KeyCode.F2, primary = true)
    }
}
