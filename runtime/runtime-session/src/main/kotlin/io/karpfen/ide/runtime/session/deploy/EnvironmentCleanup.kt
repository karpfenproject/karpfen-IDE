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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * What the cleanup after a failed or cancelled deployment did. Immutable.
 *
 * @property actions what it did, in words, in order.
 * @property leftBehind the ledger entry of the environment that stays on the runtime, or null if nothing was created.
 */
public data class CleanupReport(public val actions: List<String>, public val leftBehind: LedgerEntry?)

/**
 * The bounds of the calls of a cleanup and of a session's stop. Immutable.
 *
 * @property stop how long stopping the environment may take, including the probe that may follow the stop call.
 * @property disconnect how long closing the connection may take.
 */
internal data class CleanupBounds(val stop: Duration, val disconnect: Duration) {
    companion object {
        /** The bounds [timing] sets. */
        fun of(timing: DeployTiming): CleanupBounds = CleanupBounds(stop = timing.stopBound, disconnect = timing.disconnectBound)
    }
}

/**
 * Cleans up after a deployment that failed or was cancelled. What it does depends on the phase the deployment left the
 * environment in. The cleanup cannot be cancelled, and every call it makes is bounded in time.
 *
 * - When nothing was created, there is nothing to do and nothing is recorded.
 * - When the outcome of the creation is unknown, there is nothing to do either. The ledger gets an
 *   [LedgerDisposition.UNKNOWN] entry without a key, because an empty environment may exist.
 * - A configuring environment is disconnected. Then the [disposer] records it as left configured.
 * - An activated, running, halted or uncertain environment is stopped and then disconnected. Then the [disposer]
 *   records the phase that the stop resolved.
 * - A lost environment is disconnected and recorded as [LedgerDisposition.LOST].
 * - A stopped environment is disconnected. Then the [disposer] records it as stopped.
 *
 * After disconnecting, the cleanup also closes the connection, so that nothing of a failed deployment keeps running in
 * the connection's scope.
 *
 * @param ledger the ledger an unknown creation and a lost environment are recorded in.
 * @param disposer what happens to an environment that stays.
 * @param bounds the bounds of the calls.
 */
internal class EnvironmentCleanup(
    private val ledger: EnvironmentLedger,
    private val disposer: EnvironmentDisposer,
    private val bounds: CleanupBounds,
) {
    /** Cleans up after [run]. It runs to its end even when the caller is cancelled. */
    suspend fun clean(run: DeployRun): CleanupReport = withContext(NonCancellable) {
        val environment = run.environment
        when {
            environment != null -> cleanEnvironment(run, environment)
            run.creationUnknown -> {
                val entry = LedgerEntry(
                    run.endpoint, null, LedgerDisposition.UNKNOWN,
                    "The creation got no answer, so an empty environment may exist.",
                )
                ledger.record(entry)
                CleanupReport(listOf("Recorded that an empty environment may exist"), entry)
            }
            else -> CleanupReport(emptyList(), null)
        }
    }

    /** Cleans up the created [environment] of [run] by its phase. */
    private suspend fun cleanEnvironment(run: DeployRun, environment: RuntimeEnvironment): CleanupReport {
        val actions = mutableListOf<String>()
        val phase = environment.phase.value
        if (phase.needsStop()) {
            actions += stop(environment)
        }
        run.connection?.let { connection ->
            val closed = withTimeoutOrNull(bounds.disconnect) { connection.disconnect() } != null
            connection.close()
            actions += if (closed) "Disconnected from ${environment.key}" else "Disconnecting from ${environment.key} took too long"
        }
        val entry = if (environment.phase.value is EnvironmentPhase.Lost) {
            LedgerEntry(run.endpoint, environment.key, LedgerDisposition.LOST, "The runtime no longer knows the environment.")
                .also(ledger::record)
        } else {
            disposer.dispose(environment, run.endpoint)
        }
        actions += "Recorded ${environment.key} as ${entry.disposition.name.lowercase().replace('_', ' ')}"
        logger.info("Cleaned up {} after a failed deployment: {}", environment.key, actions)
        return CleanupReport(actions, entry)
    }

    /** Stops [environment] within the stop bound and returns what happened, in words. */
    private suspend fun stop(environment: RuntimeEnvironment): String =
        when (val outcome = withTimeoutOrNull(bounds.stop) { environment.stop() }) {
            null -> "Stopping ${environment.key} took too long"
            is CallOutcome.Done -> "Stopped ${environment.key}"
            is CallOutcome.Refused -> "Stopping ${environment.key} was refused: ${outcome.refusal.reason}"
            is CallOutcome.Failed -> "Stopping ${environment.key} failed: ${outcome.failure.describe()}"
        }

    /** True for the phases in which an engine exists or may exist, which the cleanup stops. */
    private fun EnvironmentPhase.needsStop(): Boolean =
        this == EnvironmentPhase.Activated || this == EnvironmentPhase.Running || this == EnvironmentPhase.Halted ||
            this is EnvironmentPhase.Uncertain

    private companion object {
        private val logger = LoggerFactory.getLogger(EnvironmentCleanup::class.java)
    }
}
