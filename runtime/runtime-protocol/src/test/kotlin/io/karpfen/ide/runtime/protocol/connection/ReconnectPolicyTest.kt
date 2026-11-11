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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the exponential backoff of [ReconnectPolicy] from the first delay, its cap, a constant backoff with factor 1,
 * very late retries without overflow, and the refusal of policies that make no sense.
 */
class ReconnectPolicyTest {

    @Test
    fun `the default backoff doubles from a quarter second up to ten seconds`() {
        val policy = ReconnectPolicy()

        assertEquals(
            listOf(250, 500, 1000, 2000, 4000, 8000, 10_000, 10_000).map { it.milliseconds },
            (1..8).map { policy.delayBefore(it) },
        )
    }

    @Test
    fun `a factor of one retries at a constant delay and a very late retry stays at the cap`() {
        assertEquals(List(5) { 1.seconds }, (1..5).map { ReconnectPolicy(firstDelay = 1.seconds, factor = 1.0).delayBefore(it) })
        assertEquals(10.seconds, ReconnectPolicy().delayBefore(Int.MAX_VALUE))
        assertEquals(Duration.ZERO, ReconnectPolicy(firstDelay = Duration.ZERO).delayBefore(30))
    }

    @Test
    fun `retries count from one`() {
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy().delayBefore(0) }
    }

    @Test
    fun `a policy needs a non-negative first delay, a factor of at least one, a cap above the first delay and an attempt`() {
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(firstDelay = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(factor = 0.5) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(firstDelay = 2.seconds, maxDelay = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(maxAttempts = 0) }
    }
}
