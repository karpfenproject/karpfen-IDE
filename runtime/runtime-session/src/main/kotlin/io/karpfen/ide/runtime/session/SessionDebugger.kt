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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.protocol.message.EngineMessages
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.DebuggerStates
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The debugger of a session. It pauses the engine of the running environment, resumes it and steps it to the next
 * breakpoint or to the end of the tick, and it shows where the engine is paused. A session has a debugger when its
 * deployment switched the runtime's feature `Breakpoint` on, which happens when breakpoints are switched on for the
 * project. The engine then halts at the `BREAKPOINT` blocks of the state machines by itself.
 *
 * The commands answer with the session's [SessionCommandOutcome]. Pausing is refused while the engine is paused, and
 * resuming and stepping are refused while it runs. All three are refused while the environment does not run and when
 * the session has no debugger.
 *
 * Every function may be called from any coroutine.
 */
public interface SessionDebugger {
    /** True if the session has a debugger. Without one, [state] stays unavailable and every command is refused. */
    public val isPresent: Boolean

    /**
     * What the debugger shows. It is unavailable while the environment does not run, and it follows the engine while
     * it runs. The session asks the runtime at the interval of [RuntimeSessionOptions.debuggerInterval] and right
     * after each command. An answer that cannot be read keeps the last state.
     */
    public val state: StateFlow<DebuggerState>

    /** Pauses the engine once the tick it is in has ended. */
    public suspend fun pause(): SessionCommandOutcome

    /** Lets the paused engine run freely again, up to the next breakpoint. */
    public suspend fun resume(): SessionCommandOutcome

    /**
     * Runs the paused engine on to the next breakpoint or to the end of the tick, whichever comes first. An engine
     * paused between two ticks runs one tick.
     */
    public suspend fun step(): SessionCommandOutcome
}

/**
 * How the debugger reaches the runtime. The session's port goes through its conductor, so the debugger's calls take
 * their turn with the other commands and are refused while the environment does not run.
 */
internal interface DebuggerChannel {
    /** Carries out [action] as a command of the session. */
    suspend fun command(action: DebuggerAction): SessionCommandOutcome

    /** The answer of the runtime's feature `Breakpoint` to the question for the status, or null if there is none. */
    suspend fun status(): String?
}

/**
 * The debugger of a session whose deployment switched the runtime's feature `Breakpoint` on.
 *
 * While the session runs, it asks for the engine's status through [channel] at [interval], and after each command
 * once more. A status that cannot be read is logged and keeps the last state, so it raises no problem of the session.
 * While the session does not run, the state is unavailable. A pause is noted in the journal through [journal] with
 * where the engine is paused, and an engine that runs freely again after a pause is noted as resumed. A step that
 * ends a tick does not count as resumed. One mutex orders the reads, so an older status never overwrites a newer one.
 *
 * @param session the session's state.
 * @param channel reaches the runtime.
 * @param journal adds a note to the session's journal.
 * @param interval the time between two reads while the session runs.
 */
internal class DefaultSessionDebugger(
    private val session: StateFlow<SessionState>,
    private val channel: DebuggerChannel,
    private val journal: (String) -> Unit,
    private val interval: Duration,
) : SessionDebugger {
    /** The published state. */
    private val published = MutableStateFlow<DebuggerState>(DebuggerState.Unavailable)

    /** Orders the reads and the publishing of their states. */
    private val reading = Mutex()

    override val isPresent: Boolean = true

    override val state: StateFlow<DebuggerState> = published.asStateFlow()

    /** Starts following the session in [scope]: reading at the interval while it runs, unavailable otherwise. */
    fun start(scope: CoroutineScope) {
        scope.launch(CoroutineName("session-debugger")) {
            session.collectLatest { state ->
                if (state is SessionState.Running) {
                    reading.withLock { publish(DebuggerState.Running, resumed = false) }
                    while (true) {
                        refresh()
                        delay(interval)
                    }
                } else {
                    reading.withLock { publish(DebuggerState.Unavailable, resumed = false) }
                }
            }
        }
    }

    override suspend fun pause(): SessionCommandOutcome = command(DebuggerAction.PAUSE)

    override suspend fun resume(): SessionCommandOutcome = command(DebuggerAction.RESUME)

    override suspend fun step(): SessionCommandOutcome = command(DebuggerAction.STEP)

    /** Carries out [action] unless the debugger's state refuses it, and reads the status right afterwards. */
    private suspend fun command(action: DebuggerAction): SessionCommandOutcome {
        refusal(action, published.value)?.let { return SessionCommandOutcome.Refused(session.value, it) }
        val outcome = channel.command(action)
        refresh()
        return outcome
    }

    /** Reads the status and publishes the state it shows, while the session runs. */
    private suspend fun refresh() {
        reading.withLock {
            val answer = channel.status() ?: return
            val status = EngineMessages.status(answer)
            if (status == null) {
                logger.debug("The status of the engine could not be read: {}", answer)
                return
            }
            if (session.value is SessionState.Running) publish(DebuggerStates.of(status), resumed = !status.paused)
        }
    }

    /**
     * Publishes [next] and notes in the journal what changed. A pause is noted with its place. Leaving a pause is noted
     * as resumed only if the engine [resumed], so a step that is still on its way is not.
     */
    private fun publish(next: DebuggerState, resumed: Boolean) {
        val before = published.value
        if (before == next) return
        published.value = next
        when {
            next is DebuggerState.Paused -> journal(DebuggerStates.note(next.place))
            before is DebuggerState.Paused && next == DebuggerState.Running && resumed -> journal(DebuggerStates.RESUMED)
        }
    }

    private companion object {
        /** The debugger's logger. */
        private val logger = LoggerFactory.getLogger(DefaultSessionDebugger::class.java)

        /** Why an action is refused in a state of the debugger, or null if it is not. */
        fun refusal(action: DebuggerAction, state: DebuggerState): String? = when {
            state is DebuggerState.Paused && action == DebuggerAction.PAUSE -> "The run is paused already."
            state == DebuggerState.Running && action != DebuggerAction.PAUSE -> "The run is not paused."
            else -> null
        }
    }
}

/**
 * The debugger of a session whose deployment did not switch the runtime's feature `Breakpoint` on. It is never
 * available and refuses every command.
 *
 * @param session the session's state, which a refusal names.
 */
internal class AbsentDebugger(private val session: StateFlow<SessionState>) : SessionDebugger {
    override val isPresent: Boolean = false

    override val state: StateFlow<DebuggerState> = MutableStateFlow(DebuggerState.Unavailable).asStateFlow()

    override suspend fun pause(): SessionCommandOutcome = refused()

    override suspend fun resume(): SessionCommandOutcome = refused()

    override suspend fun step(): SessionCommandOutcome = refused()

    /** The refusal of every command. */
    private fun refused(): SessionCommandOutcome =
        SessionCommandOutcome.Refused(session.value, "The run has no debugger, because breakpoints were switched off for the project.")
}
