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

import io.karpfen.ide.runtime.session.deploy.DeployTiming
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How the sessions run, with the production defaults; tests hand in a test dispatcher, its time source and a seeded
 * random, so every wait runs on virtual time and every client id is the same in every run. Immutable.
 *
 * @property dispatcher where the preparation, the conductor, the router and every wait of a session run.
 * @property timeSource the clock the journal measures the elapsed time of a session with.
 * @property random where the client ids of the sessions come from.
 * @property journalCapacity how many entries the journal of a session keeps for a late collector; older ones are dropped.
 * @property deployTiming the waits of the deployments.
 * @property subscribeAttempts how often switching observation on subscribes in all while the runtime has not processed
 *   the WebSocket handshake yet.
 * @property subscribeRetryDelay the pause between two such subscriptions.
 * @property snapshotAttempts how often a snapshot the observation needs is read in all before the model counts as out
 *   of sync.
 * @property snapshotRetryDelay the pause between two such reads.
 * @throws IllegalArgumentException if a capacity or a number of attempts is below 1 or a delay is negative.
 */
public data class RuntimeSessionOptions(
    public val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val timeSource: TimeSource = TimeSource.Monotonic,
    public val random: Random = Random.Default,
    public val journalCapacity: Int = 500,
    public val deployTiming: DeployTiming = DeployTiming(),
    public val subscribeAttempts: Int = 25,
    public val subscribeRetryDelay: Duration = 200.milliseconds,
    public val snapshotAttempts: Int = 3,
    public val snapshotRetryDelay: Duration = 1.seconds,
) {
    init {
        require(journalCapacity >= 1) { "the journal keeps at least one entry, not $journalCapacity" }
        require(subscribeAttempts >= 1) { "at least one subscription attempt, not $subscribeAttempts" }
        require(snapshotAttempts >= 1) { "at least one snapshot attempt, not $snapshotAttempts" }
        require(!subscribeRetryDelay.isNegative() && !snapshotRetryDelay.isNegative()) { "retry delays are never negative" }
    }
}
