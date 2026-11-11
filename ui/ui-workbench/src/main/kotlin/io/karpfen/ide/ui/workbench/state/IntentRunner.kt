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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the intent flows of the workbench. Each [launch] starts its flow in [scope] and returns the flow's [Job].
 *
 * Flows run one at a time, in call order. A fair mutex guards the turn, and a flow enters its queue before [launch]
 * returns. A second click therefore cannot open a second dialog while the first flow waits for an answer.
 *
 * [launchOnce] merges repeated requests for the same thing, such as closing one tab. A second request that arrives
 * while the first is queued or still asking returns the first request's job. Without that, the second request would
 * ask again after the user pressed Cancel.
 *
 * A flow that fails with an unexpected exception is logged and reported through [onFailure], which still runs within
 * the flow's turn. The failure never cancels [scope]. [exclusive] runs a suspending block in the same order, so exit
 * waits for a running flow.
 */
internal class IntentRunner(
    private val scope: CoroutineScope,
    private val onFailure: suspend (intent: String, error: Exception) -> Unit,
) {
    private val turn = Mutex()
    private val unfinished = ConcurrentHashMap<Any, Job>()

    /**
     * Launches [flow] and returns its job. [intent] names the flow in the logs. The coroutine starts undispatched, so
     * it queues for the turn's mutex before [launch] returns. Call order is therefore turn order.
     */
    fun launch(intent: String, flow: suspend () -> Unit): Job =
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

    /**
     * Works like [launch], with one difference. While a flow launched under [key] has not completed, it returns that
     * flow's job instead of launching [flow]. [key] is a value that names what the flow acts on.
     */
    fun launchOnce(key: Any, intent: String, flow: suspend () -> Unit): Job {
        unfinished[key]?.takeUnless { it.isCompleted }?.let { return it }
        val job = launch(intent, flow)
        unfinished[key] = job
        job.invokeOnCompletion { unfinished.remove(key, job) }
        return job
    }

    /**
     * Runs [block] when no flow runs, after the flows launched before it, and returns its result.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = turn.withLock { block() }

    private companion object {
        private val logger = LoggerFactory.getLogger(IntentRunner::class.java)
    }
}
