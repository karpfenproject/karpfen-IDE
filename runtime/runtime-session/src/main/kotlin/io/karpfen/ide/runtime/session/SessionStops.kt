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
 * 1. It stops the environment. This call cannot be cancelled and is bounded in time. The runtime freezes the
 *    environment, and the session never starts it again. The runtime keeps the socket open and pushes the
 *    `engineStatus` notice of the stop, and the connection ends and closes its link itself, on that notice or on the
 *    handle's stopped phase, whichever comes first. If the session was closed during a stop that failed, it records
 *    the environment as the stop left it.
 * 2. It lets the connection end by itself and then disconnects it. Disconnecting does nothing if the connection ended
 *    already. Both waits are bounded.
 * 3. It has the router read the final model, which marks the observed model as final.
 * 4. It drops the environment through the [dropper], which records it. The engine ended with the stop, so the runtime
 *    answers the drop at once. The drop runs even when the session is closed during steps 2 and 3.
 * 5. It reports [ConductorMessage.StopFinished] with the stop's outcome and, if the drop failed, the ledger entry of
 *    the environment that stays on the runtime.
 *
 * Steps 2 to 4 run only when the stop succeeded. Nothing asks the runtime about the environment after the drop, because
 * a stopped session takes no command.
 *
 * @param router the session's router.
 * @param inbox the conductor's inbox.
 * @param departure records the environment once.
 * @param dropper drops the environment after the stop.
 * @param bounds the bounds of stopping and disconnecting.
 * @param scope the session's scope.
 */
internal class EnvironmentStopper(
    private val router: PushRouter,
    private val inbox: ConductorInbox,
    private val departure: EnvironmentDeparture,
    private val dropper: EnvironmentDropper,
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
            val outcome = withContext(NonCancellable) {
                val stopped = withTimeoutOrNull(bounds.stop) { environment.stop() }
                val closedMeanwhile = departure.stopEnded(stopped is CallOutcome.Done)
                if (stopped !is CallOutcome.Done && closedMeanwhile) {
                    departure.leave(environment.key, environment.phase.value, "Left on the runtime when the session was closed during a stop that failed.")
                }
                stopped
            }
            var entry: LedgerEntry? = null
            if (outcome is CallOutcome.Done) {
                try {
                    connection?.let { letEnd(it) }
                    router.environmentStopped()
                } finally {
                    entry = dropper.drop(environment, "Stopped by the session.")
                }
            }
            inbox.offer(ConductorMessage.StopFinished(outcome, entry?.takeIf { it.leftBehind() != null }))
        }
    }

    /**
     * Lets [connection] end by itself after a stop, because the environment's stopped phase and the runtime's notice of
     * the stop end it, and the connection then closes its link itself. Then it disconnects the connection, which does
     * nothing if it ended already. Both waits are bounded.
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
