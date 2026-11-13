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

import io.karpfen.ide.runtime.protocol.connection.ConnectionState
import io.karpfen.ide.runtime.protocol.message.EngineMessages
import io.karpfen.ide.runtime.protocol.message.EngineStatus
import io.karpfen.ide.runtime.session.debug.DebuggerState
import io.karpfen.ide.runtime.session.debug.DebuggerStates
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The debugger of a session. It pauses the engine of the running environment, resumes it and steps it to the next
 * breakpoint or to the end of the tick, and it shows where the engine is paused. A session has a debugger when its
 * deployment switched the runtime's feature `Breakpoint` on, which happens when breakpoints are switched on for the
 * project. The engine then halts at the `BREAKPOINT` blocks of the state machines by itself.
 *
 * The commands answer with the session's [SessionCommandOutcome]. Pausing is refused while the engine is paused, and
 * resuming and stepping are refused while it runs. All three are refused while the environment does not run and when
 * the session has no debugger. A command that the runtime carried out shows in [state] once the runtime tells that
 * the engine waits or goes on, which may be a moment after the command returned.
 *
 * Every function may be called from any coroutine.
 */
public interface SessionDebugger {
    /** True if the session has a debugger. Without one, [state] stays unavailable and every command is refused. */
    public val isPresent: Boolean

    /**
     * What the debugger shows. It is unavailable while the environment does not run, and it follows the engine while
     * it runs. The runtime tells the session each time the engine thread starts to wait and each time it goes on. The
     * session also asks the runtime once when the environment starts to run and once after each reconnect of its
     * connection, since a message sent while the connection was down is lost. An answer that cannot be read keeps the
     * last state.
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
 * The feature `Breakpoint` brings `TickByTick` along, so the runtime pushes the engine's status each time the engine
 * thread starts to wait and each time it goes on. The debugger follows these [statuses]. It does not ask at an
 * interval, and it does not ask after a command either, because every change a command makes is pushed: a pause
 * when the engine starts to wait, a resume when it goes on, and a step when it goes on and when it waits again. A
 * command the runtime refuses changes nothing.
 *
 * It asks the runtime through [channel] only when no push can have told the status. That is once when the session
 * starts to run, since the engine may wait already and a push sent before the connection was up is lost, and once
 * after each later opening of the [connection], since a push sent while it was down is lost too. A read whose
 * answer arrives after a push is dropped, because every change of the engine is pushed, so the push is at least as new
 * as the read. A status that cannot be read is logged and keeps the last state, so it raises no problem of the
 * session.
 *
 * While the session does not run, the state is unavailable and pushes are ignored. A pause is noted in the journal
 * through [journal] with where the engine is paused, and an engine that runs freely again after a pause is noted as
 * resumed. An engine that goes on for a step still counts as paused by the runtime, so it is not noted as resumed.
 *
 * @param session the session's state.
 * @param connection the state of the session's connection.
 * @param statuses the engine statuses the runtime pushes, in arrival order.
 * @param channel reaches the runtime.
 * @param journal adds a note to the session's journal.
 */
internal class DefaultSessionDebugger(
    private val session: StateFlow<SessionState>,
    private val connection: StateFlow<ConnectionState>,
    private val statuses: Flow<EngineStatus>,
    private val channel: DebuggerChannel,
    private val journal: (String) -> Unit,
) : SessionDebugger {
    /** The published state. */
    private val published = MutableStateFlow<DebuggerState>(DebuggerState.Unavailable)

    /** Guards [pushes] and the publishing of a state. */
    private val lock = Any()

    /** How many statuses were pushed so far. A read that saw this number change while it waited is dropped. */
    private var pushes = 0L

    override val isPresent: Boolean = true

    override val state: StateFlow<DebuggerState> = published.asStateFlow()

    /**
     * Starts following the session in [scope]. It follows the pushed statuses, and while the session runs it reads
     * the status once at the start and once after each reconnect. While the session does not run, it shows the
     * debugger unavailable.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(CoroutineName("session-debugger-pushes")) {
            statuses.collect(::follow)
        }
        scope.launch(CoroutineName("session-debugger")) {
            session.collectLatest { state ->
                if (state is SessionState.Running) {
                    synchronized(lock) { if (published.value == DebuggerState.Unavailable) publish(DebuggerState.Running, resumed = false) }
                    openings().collect { read() }
                } else {
                    synchronized(lock) { publish(DebuggerState.Unavailable, resumed = false) }
                }
            }
        }
    }

    override suspend fun pause(): SessionCommandOutcome = command(DebuggerAction.PAUSE)

    override suspend fun resume(): SessionCommandOutcome = command(DebuggerAction.RESUME)

    override suspend fun step(): SessionCommandOutcome = command(DebuggerAction.STEP)

    /** Carries out [action] unless the debugger's state refuses it. The pushes that follow show what it changed. */
    private suspend fun command(action: DebuggerAction): SessionCommandOutcome {
        refusal(action, published.value)?.let { return SessionCommandOutcome.Refused(session.value, it) }
        return channel.command(action)
    }

    /**
     * The epochs of the connection's openings, the current one first. The current one stands for the start of the
     * run, and each later one for a reconnect.
     */
    private fun openings(): Flow<Int> = connection.mapNotNull { (it as? ConnectionState.Open)?.epoch }.distinctUntilChanged()

    /** Takes the pushed [status]. */
    private fun follow(status: EngineStatus) {
        synchronized(lock) {
            pushes++
            show(status)
        }
    }

    /** Reads the status and shows it, unless a push arrived while the read waited for its answer. */
    private suspend fun read() {
        val before = synchronized(lock) { pushes }
        val answer = channel.status() ?: return
        val status = EngineMessages.status(answer)
        if (status == null) {
            logger.debug("The status of the engine could not be read: {}", answer)
            return
        }
        synchronized(lock) { if (pushes == before) show(status) }
    }

    /** Publishes the state that [status] shows, while the session runs. Called under [lock]. */
    private fun show(status: EngineStatus) {
        if (session.value is SessionState.Running) publish(DebuggerStates.of(status), resumed = !status.paused)
    }

    /**
     * Publishes [next] and notes in the journal what changed. A pause is noted with its place. Leaving a pause is noted
     * as resumed only if the engine [resumed], so a step that is still on its way is not. Called under [lock].
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
