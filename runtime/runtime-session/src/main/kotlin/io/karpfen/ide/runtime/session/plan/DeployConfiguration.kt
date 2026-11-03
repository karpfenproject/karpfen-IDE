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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.document.environment.EnvironmentDefaults
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor

/**
 * The deploy configuration: the tick delay and the event time to live a deployment sends. Immutable.
 *
 * Its one home is the environment descriptor: its `tickDelayMs` and `eventTtlMs` keys are the configuration, and an
 * absent key means the default the descriptor format defines in [EnvironmentDefaults]. A deployment always sends both
 * values, so the runtime's own configured defaults never apply to a deployment from the IDE. Nothing else stores or
 * overrides them.
 *
 * @property tickDelayMs the delay between two ticks of the engine, in milliseconds; never negative.
 * @property eventTtlMs how long an event stays available, in milliseconds; 0 or a negative value keeps events until
 *   they are consumed.
 */
public data class DeployConfiguration(public val tickDelayMs: Int, public val eventTtlMs: Long) {
    init {
        require(tickDelayMs >= 0) { "a tick delay is never negative, not $tickDelayMs" }
    }

    public companion object {
        /** The tick delays the runtime accepts: it parses the value as an `Int`, and a negative delay makes no sense. */
        public val TICK_DELAY_RANGE: LongRange = 0L..Int.MAX_VALUE.toLong()

        /** The configuration of a descriptor without timing keys: the defaults of the descriptor format. */
        public val DEFAULT: DeployConfiguration =
            DeployConfiguration(EnvironmentDefaults.TICK_DELAY_MS.toInt(), EnvironmentDefaults.EVENT_TTL_MS)

        /**
         * The configuration [descriptor] states, its absent keys replaced by the defaults of the descriptor format. The
         * planner checks the tick delay's range first and reports a value outside it as a problem.
         *
         * @throws IllegalArgumentException if the tick delay lies outside [TICK_DELAY_RANGE].
         */
        public fun of(descriptor: EnvironmentDescriptor): DeployConfiguration {
            val tickDelay = tickDelayOf(descriptor)
            require(tickDelay in TICK_DELAY_RANGE) { "the tick delay $tickDelay lies outside $TICK_DELAY_RANGE" }
            return DeployConfiguration(tickDelay.toInt(), eventTtlOf(descriptor))
        }

        /** The tick delay [descriptor] states, or the default of the descriptor format; not range-checked. */
        public fun tickDelayOf(descriptor: EnvironmentDescriptor): Long =
            descriptor.tickDelayMs ?: EnvironmentDefaults.TICK_DELAY_MS

        /** The event time to live [descriptor] states, or the default of the descriptor format. */
        public fun eventTtlOf(descriptor: EnvironmentDescriptor): Long =
            descriptor.eventTtlMs ?: EnvironmentDefaults.EVENT_TTL_MS
    }
}
