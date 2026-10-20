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

/** Where one application run is. */
internal sealed interface IdePhase {
    /** The session is being prepared; no window yet. */
    data object Starting : IdePhase

    /** The prepared [session]; the main window shows it. */
    class Running(val session: IdeSession) : IdePhase

    /** The run is over: the application exits (the session's state is closed). */
    data object Exited : IdePhase
}

/**
 * The lifecycle of one application run, Compose-free (spec §1a: the window composables only render [phase] and call
 * [requestClose]): prepares the session in [scope] (the UI scope), runs the close request's unsaved-changes flow
 * (`WorkbenchState.requestExit`, launched in [scope]; one at a time), exits after [exitAfter] for launch smoke tests,
 * and closes the session's state exactly once when the run ends. Used from the UI thread (the dispatcher of [scope]),
 * like the workbench state it drives.
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

    /** The current phase. */
    val phase: StateFlow<IdePhase> = mutablePhase.asStateFlow()

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
            // The run exited while the session was being prepared (exit timer, dispose).
            session.state.close()
        }
    }

    /**
     * The window's close request: runs `requestExit` (unsaved-changes flow, settings persisted) in [scope] and exits
     * if it allows. A request while one is running returns the running flow, so a second click on the close button
     * opens no second dialog. No-op unless running.
     */
    fun requestClose(): Job {
        closeFlow?.takeIf { it.isActive }?.let { return it }
        val running = mutablePhase.value as? IdePhase.Running ?: return COMPLETED
        return scope.launch {
            if (running.session.state.requestExit()) exit()
        }.also { closeFlow = it }
    }

    /** Ends the run now (no unsaved-changes flow): the phase becomes [IdePhase.Exited] and the state is closed once. */
    fun exit() {
        val previous = mutablePhase.getAndUpdate { IdePhase.Exited }
        if (previous is IdePhase.Running) previous.session.state.close()
    }

    /** After the application returned: ends the run and cancels [scope]. */
    fun dispose() {
        exit()
        scope.cancel()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(IdeRunState::class.java)
        private val COMPLETED: Job = CompletableDeferred(Unit)
    }
}
