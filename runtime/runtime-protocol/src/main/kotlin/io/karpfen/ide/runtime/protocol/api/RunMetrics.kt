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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.message.RuntimePhase

/**
 * The numbers of one environment as `GET /observatory/metrics` answers them. The runtime counts them from the
 * activation on in every run, whether a client asks or not, and answers them without waiting for a tick boundary. All
 * durations are milliseconds with fractions. Immutable.
 *
 * @property environment the environment the numbers belong to.
 * @property phase the phase of the environment when the runtime read the numbers.
 * @property ticks the number of ticks since the first start.
 * @property tickMs the durations of the ticks. The pause between two ticks that the tick delay asks for is not part of
 *   them.
 * @property pythonMs the time the ticks waited for the Python processes of the environment. With the feature
 *   TrueParallel the waits of the branches of a split add up, so this can exceed the time of the ticks.
 * @property outsidePythonMs the time the ticks spent with everything else: finding transitions, writing values into
 *   code, reading results, actions and events.
 * @property calls the calls of code by the way they ran, with every way the client knows. A failed call counts like a
 *   successful one.
 * @property resultCaching whether the environment answers code it ran in Python before from its result cache.
 */
public data class RunMetrics(
    public val environment: EnvironmentKey,
    public val phase: RuntimePhase,
    public val ticks: Long,
    public val tickMs: TickDurations,
    public val pythonMs: Double,
    public val outsidePythonMs: Double,
    public val calls: Map<CodeWay, CallTally>,
    public val resultCaching: Boolean,
)

/**
 * The durations of the ticks of an environment, in milliseconds. Immutable.
 *
 * @property total the sum over all ticks.
 * @property mean the mean over all ticks, 0 before the first tick.
 * @property max the longest tick.
 * @property recent the latest ticks from the oldest to the newest. The runtime keeps the last 100.
 */
public data class TickDurations(
    public val total: Double,
    public val mean: Double,
    public val max: Double,
    public val recent: List<Double>,
)

/**
 * The calls of code that ran in one way. Immutable.
 *
 * @property count how many calls ran this way.
 * @property ms how long they took together, in milliseconds. It covers the run of the code only, not the writing of
 *   values into the code before the call and the reading of the result after it.
 */
public data class CallTally(public val count: Long, public val ms: Double)

/**
 * The ways in which the runtime runs the code of an `EVAL` block or a macro, as the metrics name them.
 *
 * @property wireName the name of the way in the member `calls` of the answer.
 */
public enum class CodeWay(public val wireName: String) {
    /** The runtime computes the code itself, without Python. */
    COMPUTED("computed"),

    /** A Python process of the environment, which stays open between calls, runs the code. */
    OPEN_PROCESS("openProcess"),

    /** A stored result of the same code text answers the call, and no code runs. */
    CACHE("cache"),
}
