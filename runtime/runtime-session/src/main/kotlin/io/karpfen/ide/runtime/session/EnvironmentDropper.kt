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

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import io.karpfen.ide.runtime.protocol.lifecycle.EnvironmentPhase
import io.karpfen.ide.runtime.protocol.lifecycle.RuntimeEnvironment
import io.karpfen.ide.runtime.session.deploy.DropResult
import io.karpfen.ide.runtime.session.deploy.EnvironmentDrop
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Drops the environment of a session at the end of its run, after the session read the final model, and records it
 * once through the departure. The drop cannot be cancelled and is bounded by the bound of [drop], so a session that is
 * closed meanwhile still records what became of its environment.
 *
 * A drop that fails never fails the end of the run. The environment is then recorded with the phase it is left in, and
 * the journal gets a note that says why it may stay on the runtime. A runtime that no longer knew the environment
 * leaves it recorded as lost, with a note only if the session did not know so before.
 *
 * @param drop drops one environment within its bound.
 * @param departure records the environment once.
 * @param endpoint the runtime the environment is on.
 * @param journal records a note in the session's journal.
 */
internal class EnvironmentDropper(
    private val drop: EnvironmentDrop,
    private val departure: EnvironmentDeparture,
    private val endpoint: RuntimeEndpoint,
    private val journal: (String) -> Unit,
) {
    /**
     * Drops [environment], whose run ended as [note] says, and returns the ledger entry it recorded, or null if the
     * environment was recorded before.
     */
    suspend fun drop(environment: RuntimeEnvironment, note: String): LedgerEntry? = withContext(NonCancellable) {
        departure.dropStarts()
        val key = environment.key
        val lostBefore = environment.phase.value is EnvironmentPhase.Lost
        when (val result = drop.drop(environment, endpoint)) {
            DropResult.Dropped -> departure.record(key, LedgerDisposition.DROPPED, "$note Dropped afterwards.")
            DropResult.Gone -> {
                if (!lostBefore) journal("The environment $key was gone from the runtime already when the session dropped it.")
                departure.record(key, LedgerDisposition.LOST, "$note The runtime no longer knew it when the session dropped it.")
            }
            is DropResult.Kept -> {
                journal(result.why)
                departure.record(key, LedgerDisposition.of(result.phase), "$note ${result.why}")
            }
        }
    }
}
