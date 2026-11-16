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
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.protocol.message.integralMember
import io.karpfen.ide.runtime.protocol.message.objectMember
import io.karpfen.ide.runtime.protocol.message.stringMember

/**
 * Reads the answer of `GET /observatory/metrics` by member name, as the runtime's `RunMetricsAnswer.toJson` writes it.
 *
 * - `environmentKey` is a string that is a valid [EnvironmentKey].
 * - `phase` is the wire name of a [RuntimePhase].
 * - `ticks` is an integral number of at least 0.
 * - `tickMs` is an object with the numbers `total`, `mean` and `max` and the array of numbers `recent`.
 * - `pythonMs` and `outsidePythonMs` are numbers.
 * - `calls` is an object with one member per [CodeWay], each an object with the integral number `count` of at least 0
 *   and the number `ms`. A way the client does not know is ignored, so a runtime may count more ways than it reads.
 * - `resultCaching` is `true` or `false`.
 *
 * Other members are ignored. The runtime writes every duration as a plain decimal number, which the JSON reader takes
 * as a double. The object holds no state.
 */
internal object RunMetricsReader {

    /** The numbers that [body] holds, or null if it is not the answer of the metrics route. */
    fun read(body: String): RunMetrics? {
        val members = (RuntimeJson.read(body) as? JsonReading.Read)?.value as? WireValue.WireObject ?: return null
        val key = members.stringMember(WireNames.ENVIRONMENT_KEY)?.let { runCatching { EnvironmentKey(it) }.getOrNull() } ?: return null
        val phase = members.stringMember(WireNames.PHASE)?.let(RuntimePhase::ofWireName) ?: return null
        val ticks = members.integralMember(WireNames.METRICS_TICKS)?.takeIf { it >= 0 } ?: return null
        val tickMs = members.objectMember(WireNames.METRICS_TICK_MS)?.let(::tickDurations) ?: return null
        val pythonMs = members.number(WireNames.METRICS_PYTHON_MS) ?: return null
        val outsidePythonMs = members.number(WireNames.METRICS_OUTSIDE_PYTHON_MS) ?: return null
        val calls = members.objectMember(WireNames.METRICS_CALLS)?.let(::calls) ?: return null
        val resultCaching = (members[WireNames.METRICS_RESULT_CACHING] as? WireValue.WireBoolean)?.value ?: return null
        return RunMetrics(key, phase, ticks, tickMs, pythonMs, outsidePythonMs, calls, resultCaching)
    }

    /** The tick durations of [members], or null if one of them is missing or not a number. */
    private fun tickDurations(members: WireValue.WireObject): TickDurations? {
        val recent = (members[WireNames.METRICS_RECENT] as? WireValue.WireArray)?.items
            ?.map { (it as? WireValue.WireNumber)?.value ?: return null } ?: return null
        return TickDurations(
            members.number(WireNames.METRICS_TOTAL) ?: return null,
            members.number(WireNames.METRICS_MEAN) ?: return null,
            members.number(WireNames.METRICS_MAX) ?: return null,
            recent,
        )
    }

    /** The calls of every [CodeWay] in [members], or null if a way is missing or not a count with a duration. */
    private fun calls(members: WireValue.WireObject): Map<CodeWay, CallTally>? =
        CodeWay.entries.associateWith { way ->
            val tally = members.objectMember(way.wireName) ?: return null
            CallTally(
                tally.integralMember(WireNames.METRICS_COUNT)?.takeIf { it >= 0 } ?: return null,
                tally.number(WireNames.METRICS_MS) ?: return null,
            )
        }

    /** The number in the member [name], or null if the member is missing or no number. */
    private fun WireValue.WireObject.number(name: String): Double? = (this[name] as? WireValue.WireNumber)?.value
}
