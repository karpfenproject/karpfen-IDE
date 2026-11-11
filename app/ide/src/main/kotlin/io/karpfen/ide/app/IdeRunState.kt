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
    /** The session is being prepared, and there is no window yet. */
    data object Starting : IdePhase

    /** The prepared [session] runs, and the main window shows it. */
    class Running(val session: IdeSession) : IdePhase

    /** The run is over. The session is closed, and the application exits. */
    data object Exited : IdePhase
}

/**
 * The lifecycle of one application run. It does not depend on Compose, so the window composables only render [phase]
 * and [stoppingRuntime] and call [requestClose].
 *
 * The run state prepares the session in [scope], the UI scope. On a close request it runs the unsaved-changes flow,
 * `WorkbenchState.requestExit`, launched in [scope] and one at a time. If that flow allows the exit, it shuts the
 * runtime down with `WorkbenchState.shutdownRuntime`, which ends every session and closes the host within 15 seconds.
 * For the launch smoke tests it exits after [exitAfter]. It closes the session exactly once when the run ends. Use it
 * from the UI thread, the dispatcher of [scope], like the workbench state it drives.
 *
 * @param prepare wires the session, through `IdeApplication.prepare`.
 * @param onRunning called once with the prepared session when it starts running. The app attaches the error dialogs
 *   there.
 * @param exitAfter when set, from `KARPFEN_IDE_EXIT_AFTER_MS`, the run exits after this time without the
 *   unsaved-changes flow.
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
     * True while the runtime is being shut down on exit and the shutdown has taken longer than [STOPPING_NOTE_DELAY].
     * The window then shows the short note [STOPPING_RUNTIME_NOTE].
     */
    val stoppingRuntime: StateFlow<Boolean> = mutableStoppingRuntime.asStateFlow()

    /** Why the session could not be prepared, or null. When it is set, the run has exited. */
    @Volatile
    var startupFailure: Throwable? = null
        private set

    /** Prepares the session, starts the exit timer if one is set, and returns the preparation's job. Call it once. */
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
     * Prepares the session and lets it run. A failure ends the run and is kept as [startupFailure]. If the run exited
     * while the session was being prepared, through the exit timer or [dispose], the prepared session is closed at
     * once.
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
     * The window's close request. It runs `requestExit` in [scope], which runs the unsaved-changes flow and persists
     * the settings. If that allows the exit, it shuts the runtime down and exits, also when the shutdown fails. A
     * request while one is running returns the running flow, so a second click on the close button opens no second
     * dialog and starts no second shutdown. Outside the running phase it does nothing and returns a completed job.
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

    /**
     * Shuts the runtime of [session] down. [stoppingRuntime] is true while the shutdown takes longer than
     * [STOPPING_NOTE_DELAY].
     */
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

    /**
     * Ends the run now, without the unsaved-changes flow. The phase becomes [IdePhase.Exited], and the session is
     * closed once.
     */
    fun exit() {
        val previous = mutablePhase.getAndUpdate { IdePhase.Exited }
        if (previous is IdePhase.Running) previous.session.close()
    }

    /** Ends the run and cancels [scope]. It is called after the application returned. */
    fun dispose() {
        exit()
        scope.cancel()
    }

    /** The note shown while the runtime stops on exit, and its delay. */
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
