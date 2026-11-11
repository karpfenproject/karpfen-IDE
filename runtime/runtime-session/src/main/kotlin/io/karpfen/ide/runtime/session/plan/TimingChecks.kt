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

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/** Finds a tick delay below 0 or above the largest `Int`. The runtime parses the tick delay as an `Int`. */
internal class TickDelayRangeCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val descriptor = subject.environment ?: return emptyList()
        val tickDelay = DeployConfiguration.tickDelayOf(descriptor)
        if (tickDelay in DeployConfiguration.TICK_DELAY_RANGE) return emptyList()
        return listOf(
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.TICK_DELAY_RANGE,
                "The tick delay $tickDelay ms lies outside ${DeployConfiguration.TICK_DELAY_RANGE.first} to " +
                    "${DeployConfiguration.TICK_DELAY_RANGE.last} ms, the delays the runtime accepts.",
                subject.descriptorLocation,
            ),
        )
    }
}

/** Finds a tick delay of 0, with which the engine never sleeps and keeps a processor core busy. */
internal class TickDelayZeroCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val descriptor = subject.environment ?: return emptyList()
        if (DeployConfiguration.tickDelayOf(descriptor) != 0L) return emptyList()
        return listOf(
            DeployProblem(
                Severity.WARNING,
                DeployProblemCodes.TICK_DELAY_ZERO,
                "The tick delay is 0 ms: the engine never sleeps between two ticks and keeps a processor core busy.",
                subject.descriptorLocation,
            ),
        )
    }
}

/**
 * Finds an event time to live with which events never expire. A value of 0 is reported as an info, because the runtime
 * defines 0 that way. A negative value behaves like 0 and is reported as a warning.
 */
internal class EventTtlCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val descriptor = subject.environment ?: return emptyList()
        val ttl = DeployConfiguration.eventTtlOf(descriptor)
        return when {
            ttl == 0L -> listOf(
                DeployProblem(
                    Severity.INFO,
                    DeployProblemCodes.EVENT_TTL_NEVER_EXPIRES,
                    "The event time to live is 0 ms: events never expire and stay until a state machine consumes them.",
                    subject.descriptorLocation,
                ),
            )
            ttl < 0L -> listOf(
                DeployProblem(
                    Severity.WARNING,
                    DeployProblemCodes.EVENT_TTL_NEVER_EXPIRES,
                    "The event time to live $ttl ms is negative and behaves like 0: events never expire.",
                    subject.descriptorLocation,
                ),
            )
            else -> emptyList()
        }
    }
}
