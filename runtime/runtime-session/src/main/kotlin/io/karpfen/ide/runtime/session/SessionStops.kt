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
import io.karpfen.ide.runtime.protocol.connection.RuntimeConnection
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.session.deploy.CleanupBounds
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import io.karpfen.ide.runtime.session.problem.DeployProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stops the environment of a session on a coroutine of its own and reports the end to the conductor's inbox. Meanwhile
 * the conductor shows the session as stopping and keeps answering commands. A stop that was begun always runs. Its
 * coroutine starts at once, even if the session is closed right after, and the stop call itself cannot be cancelled.
 * So closing during a stop never leaves the environment both running and unrecorded.
 *
 * 1. It stops the environment. This call cannot be cancelled and is bounded in time. The runtime then closes the
 *    connection with `Environment stopped`.
 * 2. It records the environment as stopped once it stopped. If the session was closed during a stop that failed, it
 *    records the environment as the stop left it.
 * 3. It lets the connection end by itself and then disconnects it. Disconnecting does nothing if the connection ended
 *    already. Both waits are bounded.
 * 4. It has the router read the final model, which marks the observed model as final.
 * 5. It reports [ConductorMessage.StopFinished] with the stop's outcome and the ledger entry.
 *
 * Steps 3 and 4 run only when the stop succeeded.
 *
 * @param router the session's router.
 * @param inbox the conductor's inbox.
 * @param departure records the environment once.
 * @param bounds the bounds of stopping and disconnecting.
 * @param scope the session's scope.
 */
internal class EnvironmentStopper(
    private val router: PushRouter,
    private val inbox: ConductorInbox,
    private val departure: EnvironmentDeparture,
    private val bounds: CleanupBounds,
    private val scope: CoroutineScope,
) {
    /**
     * Stops [environment], whose connection is [connection], and reports the end. The coroutine runs in the caller's
     * thread up to its first suspension, which is inside the stop call, before this function returns. So a close that
     * follows cannot cancel it before the stop call is made.
     */
    fun launch(environment: RuntimeEnvironment, connection: RuntimeConnection?) {
        departure.stopStarts()
        scope.launch(CoroutineName("session-stop"), start = CoroutineStart.UNDISPATCHED) {
            var entry: LedgerEntry? = null
            val outcome = withContext(NonCancellable) {
                val stopped = withTimeoutOrNull(bounds.stop) { environment.stop() }
                val closedMeanwhile = departure.stopEnded()
                entry = when {
                    stopped is CallOutcome.Done -> departure.record(environment.key, LedgerDisposition.STOPPED, "Stopped by the session.")
                    closedMeanwhile ->
                        departure.leave(environment.key, environment.phase.value, "Left on the runtime when the session was closed during a stop that failed.")
                    else -> null
                }
                stopped
            }
            if (outcome is CallOutcome.Done) {
                connection?.let { letEnd(it) }
                router.environmentStopped()
            }
            inbox.offer(ConductorMessage.StopFinished(outcome, entry))
        }
    }

    /**
     * Lets [connection] end by itself after a stop, because the runtime closes it and the environment's phase ends it.
     * Then it disconnects the connection, which does nothing if it ended already. Both waits are bounded.
     */
    suspend fun letEnd(connection: RuntimeConnection) {
        withTimeoutOrNull(bounds.disconnect) { connection.state.first { it is ConnectionState.Ended } }
        withTimeoutOrNull(bounds.disconnect) { connection.disconnect() }
    }
}

/**
 * A stop the conductor runs.
 *
 * @property before the state before the stop, restored if it does not stop the environment.
 * @property failure the problem the session failed with, if it stops because of it, or null.
 * @property waiters who waits for the stop's end.
 */
internal class StopInProgress(val before: SessionState, val failure: DeployProblem?, val waiters: MutableList<StopWaiter>)

/** Who waits for the end of a stop. */
internal sealed interface StopWaiter {
    /** Hears that the stop ended with [outcome]. */
    fun answer(outcome: SessionCommandOutcome)

    /**
     * The caller of the stop command, who hears the outcome.
     *
     * @property reply where it goes.
     */
    data class Command(val reply: CompletableDeferred<SessionCommandOutcome>) : StopWaiter {
        override fun answer(outcome: SessionCommandOutcome) {
            reply.complete(outcome)
        }
    }

    /**
     * A shutdown, which goes on whatever the outcome.
     *
     * @property reply where the end goes.
     */
    data class Shutdown(val reply: CompletableDeferred<Unit>) : StopWaiter {
        override fun answer(outcome: SessionCommandOutcome) {
            reply.complete(Unit)
        }
    }

    /** Nobody waits, because the session stops after it failed. */
    data object None : StopWaiter {
        override fun answer(outcome: SessionCommandOutcome) = Unit
    }
}
