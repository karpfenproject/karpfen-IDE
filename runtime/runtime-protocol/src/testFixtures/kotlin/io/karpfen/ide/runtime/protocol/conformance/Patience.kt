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
package io.karpfen.ide.runtime.protocol.conformance

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long a scenario waits for the runtime, per kind of wait. Every bound is generous on purpose: a real runtime is
 * slower than its tick delay (Python, container scheduling, port forwarding, a loaded machine), and a bound that passes
 * fails the scenario naming what it awaited, never as a wrong assertion. The bounds are upper limits, never asserted
 * durations.
 *
 * @property deploy a deployment, from creating the environment to the confirmed start.
 * @property reaction the reaction to one call or one event: a push, a stack, a changed snapshot.
 * @property burst a sequence of reactions over several ticks, such as a burst of the metronome.
 * @property crowd a step of a scenario with several environments at once.
 * @property scenario a whole scenario.
 * @property pollEvery the pause between two polls of a waiting helper.
 * @throws IllegalArgumentException if a bound is not positive.
 */
public data class Patience(
    public val deploy: Duration,
    public val reaction: Duration,
    public val burst: Duration,
    public val crowd: Duration,
    public val scenario: Duration,
    public val pollEvery: Duration,
) {
    init {
        require(listOf(deploy, reaction, burst, crowd, scenario, pollEvery).all { it.isPositive() }) {
            "every bound must be positive: $this"
        }
    }

    public companion object {
        /**
         * The patience with a real runtime, which tests on virtual time may use too because waits cost nothing there:
         * 60 s for a deployment, 30 s for a reaction, 60 s for a burst, 180 s for a step with several environments, 5 min
         * for a scenario, polling every 250 ms.
         */
        public val LIVE: Patience = Patience(
            deploy = 60.seconds,
            reaction = 30.seconds,
            burst = 60.seconds,
            crowd = 180.seconds,
            scenario = 5.minutes,
            pollEvery = 250.milliseconds,
        )
    }
}
