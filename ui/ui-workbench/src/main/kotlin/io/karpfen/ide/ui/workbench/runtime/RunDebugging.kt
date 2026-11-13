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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.runtime.session.SessionDebugger
import io.karpfen.ide.runtime.session.debug.DebuggerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The debugger of the selected run, as the Runtime tool window and the Run menu show and steer it. It follows the
 * debugger of the selected run's session, and it is unavailable while no run is selected, before the selected run was
 * launched and while its environment does not run. The commands act on the debugger of the run that is selected when
 * they are called, and launch their work in [scope]. A refusal of the session is not shown, because the commands are
 * only offered where they apply.
 *
 * Use the class from the UI thread only.
 *
 * @param scope where the state is collected and the commands run.
 * @param selected the selected run.
 */
public class RunDebugging internal constructor(private val scope: CoroutineScope, private val selected: StateFlow<RunEntry?>) {

    /** What the debugger of the selected run shows. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val state: StateFlow<DebuggerState> =
        selected.flatMapLatest { entry -> entry?.session?.debugger?.state ?: flowOf(DebuggerState.Unavailable) }
            .stateIn(scope, SharingStarted.Eagerly, DebuggerState.Unavailable)

    /** Pauses the selected run. */
    public fun pause(): Job = act { it.pause() }

    /** Resumes the selected run. */
    public fun resume(): Job = act { it.resume() }

    /** Steps the selected run to the next breakpoint or to the end of the tick. */
    public fun step(): Job = act { it.step() }

    /** Runs [command] on the debugger of the selected run, if it has a session. */
    private fun act(command: suspend (SessionDebugger) -> Unit): Job {
        val debugger = selected.value?.session?.debugger
        return scope.launch { debugger?.let { command(it) } }
    }
}
