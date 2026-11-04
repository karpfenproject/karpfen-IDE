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
package io.karpfen.ide.runtime.session.observation

import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import kotlin.time.Duration

/**
 * The runtime calls the observation needs, for one environment and one client id. The session implements it over the
 * environment handle and the client id of its connection.
 */
internal interface ObservationPort {
    /** Subscribes the client to every object and stack change of the environment. */
    suspend fun subscribe(): CallOutcome<Unit>

    /** Ends the model subscription of the client. */
    suspend fun unsubscribe(): CallOutcome<Unit>

    /** Reads a snapshot of the model. */
    suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot>
}

/**
 * How persistently the observation retries. An attempt count is the number of calls in all, the first one included.
 *
 * @property subscribeAttempts the calls of a subscription that the runtime refuses because it has not processed the
 *   handshake yet (no open WebSocket session).
 * @property subscribeRetryDelay the pause between two such calls.
 * @property snapshotAttempts the snapshot reads while observation is on, until the model counts as out of sync.
 * @property snapshotRetryDelay the pause between two snapshot reads.
 * @property updateBuffer how many model updates wait for a slow collector before the observation waits for it.
 */
internal data class ObservationSettings(
    val subscribeAttempts: Int,
    val subscribeRetryDelay: Duration,
    val snapshotAttempts: Int,
    val snapshotRetryDelay: Duration,
    val updateBuffer: Int = DEFAULT_UPDATE_BUFFER,
) {
    init {
        require(subscribeAttempts >= 1) { "at least one subscription attempt, not $subscribeAttempts" }
        require(snapshotAttempts >= 1) { "at least one snapshot attempt, not $snapshotAttempts" }
        require(!subscribeRetryDelay.isNegative() && !snapshotRetryDelay.isNegative()) { "retry delays are never negative" }
        require(updateBuffer >= 0) { "the update buffer is never negative, not $updateBuffer" }
    }

    companion object {
        /** The updates that wait for a slow collector by default. */
        const val DEFAULT_UPDATE_BUFFER: Int = 64
    }
}

/** What the observation reports to its owner besides the model and its updates. */
internal sealed interface ObservationNotice {
    /**
     * The model was read from a snapshot.
     *
     * @property reason why the snapshot was read, or null for a manual pull or the final read after the stop.
     * @property revision the snapshot's revision.
     */
    data class Synchronized(val reason: SyncReason?, val revision: Long) : ObservationNotice

    /**
     * Observation is on, but no snapshot could be read to re-synchronize; the mirror stays as it was until the next
     * pull.
     *
     * @property reason why, in a plain sentence.
     */
    data class OutOfSync(val reason: String) : ObservationNotice

    /**
     * Something that should not happen, such as a revision gap or a key the schema does not explain.
     *
     * @property detail what happened, in a plain sentence.
     */
    data class Anomaly(val detail: String) : ObservationNotice
}

/** Receives the [ObservationNotice]s of a [ModelObserver], on the observer's coroutine; it never suspends. */
internal fun interface ObservationListener {
    /** Takes [notice]. */
    fun notice(notice: ObservationNotice)
}
