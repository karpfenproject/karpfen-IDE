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

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** What came of dropping an environment. Immutable. */
internal sealed interface DropResult {
    /** The runtime dropped it and keeps nothing of it. */
    data object Dropped : DropResult

    /** The runtime no longer knew it, because it was lost before or somebody else dropped it first. */
    data object Gone : DropResult

    /**
     * The environment may stay on the runtime, because the drop failed or took too long.
     *
     * @property phase the phase the environment is left in, as the handle keeps it.
     * @property why what went wrong, as a sentence that names the environment and the runtime.
     */
    data class Kept(val phase: EnvironmentPhase, val why: String) : DropResult
}

/**
 * Drops one environment and tells what came of it. The drop cannot be cancelled and is bounded by [bound]. The runtime
 * answers after the engine ended, which takes up to about 5 s for an engine that still runs and hardly any time for a
 * stopped one.
 *
 * The handle answers a drop locally for an environment that is lost already, which counts as [DropResult.Gone], as
 * does a rejection for an unknown environment. A runtime that answers the route with 404 is older than the IDE and has
 * no drop, and the environment stays there. Any other failure, and a drop that takes longer than [bound], leave the
 * environment on the runtime as far as the IDE knows.
 *
 * The object holds no state, and [drop] may be called from any coroutine.
 *
 * @param bound how long a drop may take.
 */
internal class EnvironmentDrop(private val bound: Duration) {
    /** Drops [environment] on the runtime at [endpoint]. */
    suspend fun drop(environment: RuntimeEnvironment, endpoint: RuntimeEndpoint): DropResult = withContext(NonCancellable) {
        if (environment.phase.value is EnvironmentPhase.Lost) return@withContext DropResult.Gone
        val outcome = withTimeoutOrNull(bound) { environment.drop() }
        val key = environment.key
        val address = endpoint.httpBase
        when (outcome) {
            is CallOutcome.Done -> DropResult.Dropped
            null -> DropResult.Kept(
                environment.phase.value,
                "Dropping the environment $key took longer than $bound, so it may stay on the runtime at $address.",
            )
            is CallOutcome.Refused -> DropResult.Kept(
                outcome.refusal.phase,
                "The environment $key could not be dropped (${outcome.refusal.reason}), so it may stay on the runtime at $address.",
            )
            is CallOutcome.Failed -> failed(outcome.failure, environment, address)
        }
    }

    /** The result of a drop of [environment] on the runtime at [address] that failed with [failure]. */
    private fun failed(failure: RuntimeFailure, environment: RuntimeEnvironment, address: String): DropResult {
        val key = environment.key
        val phase = environment.phase.value
        return when {
            failure is RuntimeFailure.Rejected && failure.kind == RejectionKind.UNKNOWN_ENVIRONMENT -> DropResult.Gone
            failure is RuntimeFailure.UnexpectedStatus && failure.status == NOT_FOUND -> DropResult.Kept(
                phase,
                "The runtime at $address cannot drop environments, so it is older than the IDE; the environment $key stays there.",
            )
            else -> DropResult.Kept(phase, "Dropping the environment $key failed, so it may stay on the runtime at $address. ${failure.describe()}")
        }
    }

    private companion object {
        /** The status of a route the runtime does not have. */
        const val NOT_FOUND = 404
    }
}
