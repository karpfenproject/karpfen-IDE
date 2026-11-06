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
package io.karpfen.ide.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where one application run is. */
internal sealed interface IdePhase {
    /** The session is being prepared; no window yet. */
    data object Starting : IdePhase

    /** The prepared [session]; the main window shows it. */
    class Running(val session: IdeSession) : IdePhase

    /** The run is over: the application exits (the session is closed). */
    data object Exited : IdePhase
}

/**
 * The lifecycle of one application run, Compose-free, so the window composables only render [phase] and
 * [stoppingRuntime] and call [requestClose]: prepares the session in [scope] (the UI scope), runs the close request's
 * unsaved-changes flow (`WorkbenchState.requestExit`, launched in [scope]; one at a time) and then shuts the runtime
 * down (`WorkbenchState.shutdownRuntime`, which ends every session and closes the host within 15 seconds), exits after
 * [exitAfter] for launch smoke tests, and closes the session exactly once when the run ends. Used from the UI thread
 * (the dispatcher of [scope]), like the workbench state it drives.
 *
 * @param prepare wires the session (`IdeApplication.prepare`).
 * @param onRunning called once with the prepared session when it starts running (the app attaches the error dialogs).
 * @param exitAfter when set (`KARPFEN_IDE_EXIT_AFTER_MS`), the run exits after this time without the unsaved-changes
 *   flow.
 */
internal class IdeRunState(
    private val scope: CoroutineScope,
    private val prepare: suspend () -> IdeSession,
    private val onRunning: (IdeSession) -> Unit = {},
    private val exitAfter: Duration? = null,
) {
    private val mutablePhase = MutableStateFlow<IdePhase>(IdePhase.Starting)
    private var closeFlow: Job? = null

    private val mutableStoppingRuntime = MutableStateFlow(false)

    /** The current phase. */
    val phase: StateFlow<IdePhase> = mutablePhase.asStateFlow()

    /**
     * True while the runtime is being shut down on exit and that has taken longer than [STOPPING_NOTE_DELAY]: the window
     * then shows a short note ([STOPPING_RUNTIME_NOTE]).
     */
    val stoppingRuntime: StateFlow<Boolean> = mutableStoppingRuntime.asStateFlow()

    /** Why the session could not be prepared (the run then exited), or null. */
    @Volatile
    var startupFailure: Throwable? = null
        private set

    /** Prepares the session and starts the exit timer; returns the preparation's job. Call once. */
    fun start(): Job {
        exitAfter?.let { delayTime ->
            scope.launch {
                delay(delayTime)
                log.info("Exiting after {} as requested by KARPFEN_IDE_EXIT_AFTER_MS", delayTime)
                exit()
            }
        }
        return scope.launch { startSession() }
    }

    /**
     * Prepares the session and lets it run. A failure ends the run and is kept as [startupFailure]; if the run exited
     * while the session was being prepared (exit timer, dispose), the prepared session is closed at once.
     */
    private suspend fun startSession() {
        val session = try {
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("The IDE could not start", e)
            startupFailure = e
            exit()
            return
        }
        if (mutablePhase.compareAndSet(IdePhase.Starting, IdePhase.Running(session))) {
            onRunning(session)
        } else {
            session.close()
        }
    }

    /**
     * The window's close request: runs `requestExit` (unsaved-changes flow, settings persisted) in [scope] and, if it
     * allows, shuts the runtime down and exits, also when the shutdown fails. A request while one is running returns
     * the running flow, so a second click on the close button opens no second dialog and starts no second shutdown.
     * No-op unless running.
     */
    fun requestClose(): Job {
        closeFlow?.takeIf { it.isActive }?.let { return it }
        val running = mutablePhase.value as? IdePhase.Running ?: return COMPLETED
        return scope.launch {
            if (running.session.state.requestExit()) {
                try {
                    shutDownRuntime(running.session)
                } finally {
                    exit()
                }
            }
        }.also { closeFlow = it }
    }

    /** Shuts the runtime of [session] down; [stoppingRuntime] turns true once that takes longer than the note's delay. */
    private suspend fun shutDownRuntime(session: IdeSession) {
        val note = scope.launch {
            delay(STOPPING_NOTE_DELAY)
            mutableStoppingRuntime.value = true
        }
        try {
            session.state.shutdownRuntime()
        } finally {
            note.cancel()
            mutableStoppingRuntime.value = false
        }
    }

    /** Ends the run now (no unsaved-changes flow): the phase becomes [IdePhase.Exited], and the session closes once. */
    fun exit() {
        val previous = mutablePhase.getAndUpdate { IdePhase.Exited }
        if (previous is IdePhase.Running) previous.session.close()
    }

    /** After the application returned: ends the run and cancels [scope]. */
    fun dispose() {
        exit()
        scope.cancel()
    }

    companion object {
        /** How long the runtime may take to shut down on exit before the window says so. */
        val STOPPING_NOTE_DELAY: Duration = 1.seconds

        /** The note the window shows while the runtime shuts down on exit. */
        const val STOPPING_RUNTIME_NOTE: String = "Stopping the runtime…"

        private val log = LoggerFactory.getLogger(IdeRunState::class.java)

        /** The job of a close request with nothing to do. */
        private val COMPLETED: Job = CompletableDeferred(Unit)
    }
}
