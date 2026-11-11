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
package io.karpfen.ide.runtime.protocol.connection

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How a connection retries. It uses an exponential backoff that starts at [firstDelay]. Each further retry multiplies
 * the delay by [factor], and no delay is longer than [maxDelay]. The connection gives up after [maxAttempts] failed
 * attempts in a row.
 *
 * @property firstDelay the delay before the first retry.
 * @property factor what each further retry's delay is multiplied by.
 * @property maxDelay the longest delay.
 * @property maxAttempts the number of failed attempts after which the connection gives up.
 * @throws IllegalArgumentException if [firstDelay] is negative, [factor] is below 1, [maxDelay] is shorter than
 *   [firstDelay] or [maxAttempts] is below 1.
 */
public data class ReconnectPolicy(
    public val firstDelay: Duration = 250.milliseconds,
    public val factor: Double = 2.0,
    public val maxDelay: Duration = 10.seconds,
    public val maxAttempts: Int = 20,
) {
    init {
        require(!firstDelay.isNegative()) { "the first delay must not be negative: $firstDelay" }
        require(factor >= 1.0) { "the factor must be at least 1: $factor" }
        require(maxDelay >= firstDelay) { "the longest delay must not be shorter than the first: $maxDelay < $firstDelay" }
        require(maxAttempts >= 1) { "at least one attempt is needed: $maxAttempts" }
    }

    /**
     * The delay before the retry with the number [attempt], counting from 1. The first retry waits [firstDelay]. Each
     * further retry multiplies that by [factor], up to [maxDelay]. The first connection attempt is not a retry and
     * waits for nothing.
     *
     * @throws IllegalArgumentException if [attempt] is below 1.
     */
    public fun delayBefore(attempt: Int): Duration {
        require(attempt >= 1) { "retries count from 1, not $attempt" }
        val millis = firstDelay.toDouble(DurationUnit.MILLISECONDS) * factor.pow(attempt - 1)
        return if (millis >= maxDelay.toDouble(DurationUnit.MILLISECONDS)) maxDelay else millis.milliseconds
    }
}
