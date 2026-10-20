package io.karpfen.ide.ui.workbench.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Runs the workbench's intent flows (decision 42 of the work-package plan): each [launch] starts its flow in [scope]
 * and returns the flow's [Job]; flows run one at a time, in call order (a fair mutex, entered before the call
 * returns), so a second click cannot open a second dialog while the first flow waits for an answer. A flow that fails
 * with an unexpected exception is logged and reported through [onFailure] (still holding the turn), never cancelling
 * [scope]. [exclusive] runs a suspending block in the same order (exit waits for a running flow).
 */
internal class IntentRunner(
    private val scope: CoroutineScope,
    private val onFailure: suspend (intent: String, error: Exception) -> Unit,
) {
    private val turn = Mutex()

    /** Launches the flow [intent] (a name for logs) and returns its job. */
    fun launch(intent: String, flow: suspend () -> Unit): Job =
        // UNDISPATCHED: the coroutine queues for the mutex before launch returns, so call order is turn order.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            turn.withLock {
                try {
                    flow()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    logger.error("The workbench intent '{}' failed", intent, e)
                    onFailure(intent, e)
                }
            }
        }

    /** Runs [block] when no flow runs (after the flows launched before), and returns its result. */
    suspend fun <T> exclusive(block: suspend () -> T): T = turn.withLock { block() }

    private companion object {
        private val logger = LoggerFactory.getLogger(IntentRunner::class.java)
    }
}
