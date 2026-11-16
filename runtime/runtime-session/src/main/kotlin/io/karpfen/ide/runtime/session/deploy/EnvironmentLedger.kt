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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/** What became of an environment this IDE created. */
public enum class LedgerDisposition {
    /** Left on the runtime while configuring. */
    LEFT_CONFIGURED,

    /** Left on the runtime activated, not started. */
    LEFT_ACTIVATED,

    /** Left on the runtime running. */
    LEFT_RUNNING,

    /** Left on the runtime halted. It was started, but its engine ended on its own. */
    LEFT_HALTED,

    /** Stopped, and left on the runtime frozen, because dropping it failed or was not tried. */
    STOPPED,

    /** Dropped. The runtime keeps nothing of it. */
    DROPPED,

    /** The runtime no longer knows it. */
    LOST,

    /** Unknown, because a call left open whether the environment exists or in which phase it is. */
    UNKNOWN,
    ;

    public companion object {
        /** The disposition of an environment left in [phase]. */
        public fun of(phase: EnvironmentPhase): LedgerDisposition =
            when (phase) {
                is EnvironmentPhase.Configuring -> LEFT_CONFIGURED
                EnvironmentPhase.Activated -> LEFT_ACTIVATED
                EnvironmentPhase.Running -> LEFT_RUNNING
                EnvironmentPhase.Halted -> LEFT_HALTED
                EnvironmentPhase.Stopped -> STOPPED
                EnvironmentPhase.Dropped -> DROPPED
                is EnvironmentPhase.Lost -> LOST
                is EnvironmentPhase.Uncertain -> UNKNOWN
            }
    }
}

/**
 * One record of the ledger. Immutable.
 *
 * @property endpoint the runtime the environment was created on.
 * @property key the environment's key. It is null if the outcome of the creation was left open. Then the environment
 *   may exist without the IDE knowing its key.
 * @property disposition what became of it.
 * @property note what happened, in words.
 */
public data class LedgerEntry(
    public val endpoint: RuntimeEndpoint,
    public val key: EnvironmentKey?,
    public val disposition: LedgerDisposition,
    public val note: String,
) {
    /**
     * The INFO that the environment stays on the runtime, with its key and phase, or null if it does not stay because
     * it was dropped or the runtime no longer knows it.
     */
    public fun leftBehind(): DeployProblem? {
        if (disposition == LedgerDisposition.LOST || disposition == LedgerDisposition.DROPPED) return null
        val environment = key?.let { "Environment $it" } ?: "An environment of unknown key"
        return DeployProblem(
            Severity.INFO,
            DeployProblemCodes.ENVIRONMENT_LEFT_BEHIND,
            "$environment stays on the runtime at ${endpoint.httpBase} (${describe(disposition)}). $note",
            null,
        )
    }

    /** [disposition] in words. */
    private fun describe(disposition: LedgerDisposition): String =
        when (disposition) {
            LedgerDisposition.LEFT_CONFIGURED -> "configuring"
            LedgerDisposition.LEFT_ACTIVATED -> "activated"
            LedgerDisposition.LEFT_RUNNING -> "running"
            LedgerDisposition.LEFT_HALTED -> "halted"
            LedgerDisposition.STOPPED -> "stopped"
            LedgerDisposition.DROPPED -> "dropped"
            LedgerDisposition.LOST -> "lost"
            LedgerDisposition.UNKNOWN -> "in an unknown phase"
        }
}

/**
 * The record of every environment this IDE process created, with what became of it, in the order they were recorded. It
 * is a plain class, not a singleton. The composition root holds one per process and hands it to whatever records.
 *
 * [record] may be called from any thread. [entries] is updated atomically.
 */
public class EnvironmentLedger {
    /** The entries. [record] is their only writer. */
    private val recorded = MutableStateFlow<List<LedgerEntry>>(emptyList())

    /** The entries recorded so far, oldest first. */
    public val entries: StateFlow<List<LedgerEntry>> = recorded.asStateFlow()

    /** Records [entry]. */
    public fun record(entry: LedgerEntry) {
        recorded.update { it + entry }
    }
}

/**
 * Decides what happens to an environment that stays on the runtime after a deployment failed or a session ended. It
 * returns the ledger entry it recorded.
 *
 * It is called from a cleanup that cannot be cancelled. It returns within the bounds of its own calls.
 */
public fun interface EnvironmentDisposer {
    /** Disposes of [environment] on the runtime at [endpoint] and returns the entry it recorded. */
    public suspend fun dispose(environment: RuntimeEnvironment, endpoint: RuntimeEndpoint): LedgerEntry
}

/**
 * Keeps environments on the runtime instead of dropping them. It records each one in [ledger] with the phase it was
 * left in. [LedgerEntry.leftBehind] is the INFO that tells the user so.
 *
 * @param ledger the ledger the environments are recorded in.
 */
public class KeepEnvironments(private val ledger: EnvironmentLedger) : EnvironmentDisposer {
    override suspend fun dispose(environment: RuntimeEnvironment, endpoint: RuntimeEndpoint): LedgerEntry {
        val phase = environment.phase.value
        val entry = LedgerEntry(endpoint, environment.key, LedgerDisposition.of(phase), "Kept in the phase ${nameOf(phase)}.")
        ledger.record(entry)
        return entry
    }

    /** [phase] in words. */
    private fun nameOf(phase: EnvironmentPhase): String =
        when (phase) {
            is EnvironmentPhase.Configuring -> "configuring"
            EnvironmentPhase.Activated -> "activated"
            EnvironmentPhase.Running -> "running"
            EnvironmentPhase.Halted -> "halted"
            EnvironmentPhase.Stopped -> "stopped"
            EnvironmentPhase.Dropped -> "dropped"
            is EnvironmentPhase.Lost -> "lost (${phase.reason})"
            is EnvironmentPhase.Uncertain -> "uncertain (${phase.reason})"
        }
}

/**
 * Drops environments, so that the runtime keeps nothing of a deployment that failed. It records each one in [ledger],
 * as dropped, as lost when the runtime no longer knew it, or with the phase it was left in when the drop failed. Only
 * that last entry has an INFO from [LedgerEntry.leftBehind], and its note says why the drop failed.
 *
 * @param ledger the ledger the environments are recorded in.
 * @param bound how long a drop may take.
 */
public class DropEnvironments(private val ledger: EnvironmentLedger, bound: Duration) : EnvironmentDisposer {
    /** Drops one environment. */
    private val drop = EnvironmentDrop(bound)

    override suspend fun dispose(environment: RuntimeEnvironment, endpoint: RuntimeEndpoint): LedgerEntry {
        val entry = when (val result = drop.drop(environment, endpoint)) {
            DropResult.Dropped -> LedgerEntry(endpoint, environment.key, LedgerDisposition.DROPPED, "Dropped after the deployment ended.")
            DropResult.Gone -> LedgerEntry(endpoint, environment.key, LedgerDisposition.LOST, "The runtime no longer knew it when it was dropped.")
            is DropResult.Kept -> LedgerEntry(endpoint, environment.key, LedgerDisposition.of(result.phase), result.why)
        }
        ledger.record(entry)
        return entry
    }
}
