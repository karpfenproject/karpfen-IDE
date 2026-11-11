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
package io.karpfen.ide.architecture

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Judges the time limits of the timeouts of a test. These are JUnit's timeout assertions (`assertTimeout`,
 * `assertTimeoutPreemptively`), JUnit's `@Timeout` annotation and the `timeout` argument of the coroutine test runner
 * `runTest`. A timeout measures nothing the test sees. Whether it is a time budget or a guard against a test that hangs
 * shows only in its limit. A limit shorter than [GENEROUS] is tight and counts as a time budget, while a functional
 * test that waits for an event gives its timeout seconds. One rule holds for all three kinds of timeout.
 *
 * Only a limit written as a literal is judged: a Kotlin duration such as `100.milliseconds`, a java.time duration such
 * as `Duration.ofMillis(100)`, also with a static import, or the value and unit of `@Timeout`, whose unit defaults to
 * seconds. A limit held in a named constant is not judged, and such a timeout counts as generous.
 */
internal object TimeLimits {

    /** The shortest limit of a generous timeout, one that guards against hanging instead of asserting a budget. */
    val GENEROUS: Duration = 1.seconds

    /** A Kotlin duration literal: a number, a dot and the name of a unit. Group one holds the number, group two the unit. */
    private val KOTLIN_DURATION =
        Regex("""(?<![\w.])(\d[\d_]*(?:\.\d+)?)[ \t]*\.[ \t]*(nanoseconds|microseconds|milliseconds|seconds|minutes|hours|days)\b""")

    /** A java.time duration literal, such as `Duration.ofMillis(100)`. Group one holds the unit, group two the number. */
    private val JAVA_DURATION = Regex("""(?<![\w.])(?:Duration\.)?of(Nanos|Millis|Seconds|Minutes|Hours|Days)\(\s*(\d[\d_]*)L?\s*\)""")

    /** The name of a timeout assertion of JUnit, followed by its argument list. */
    private val TIMEOUT_ASSERTION = Regex("""\bassertTimeout\w*(?=[ \t]*\()""")

    /** The name of the coroutine test runner, followed by its argument list. */
    private val RUN_TEST = Regex("""\brunTest(?=[ \t]*\()""")

    /** The named argument `timeout` and its value up to the next comma or the end of the argument list. */
    private val TIMEOUT_ARGUMENT = Regex("""\btimeout\s*=\s*([^,]*)""")

    /** The units of Kotlin duration literals by name. */
    private val KOTLIN_UNITS = mapOf(
        "nanoseconds" to DurationUnit.NANOSECONDS, "microseconds" to DurationUnit.MICROSECONDS,
        "milliseconds" to DurationUnit.MILLISECONDS, "seconds" to DurationUnit.SECONDS, "minutes" to DurationUnit.MINUTES,
        "hours" to DurationUnit.HOURS, "days" to DurationUnit.DAYS,
    )

    /** The units of java.time duration literals by the suffix of their factory. */
    private val JAVA_UNITS = mapOf(
        "Nanos" to DurationUnit.NANOSECONDS, "Millis" to DurationUnit.MILLISECONDS, "Seconds" to DurationUnit.SECONDS,
        "Minutes" to DurationUnit.MINUTES, "Hours" to DurationUnit.HOURS, "Days" to DurationUnit.DAYS,
    )

    /**
     * True if [code], the code of a function with comments and string literals blanked, calls a timeout assertion or
     * `runTest` with a tight literal limit.
     */
    fun hasTightTimeoutIn(code: String): Boolean {
        val assertions = TIMEOUT_ASSERTION.findAll(code).map { code.textOf(CodeRegions.argumentList(code, it.range.last + 1)) }
        val runners = RUN_TEST.findAll(code).mapNotNull { runner ->
            TIMEOUT_ARGUMENT.find(code.textOf(CodeRegions.argumentList(code, runner.range.last + 1)))?.groupValues?.get(1)
        }
        return (assertions + runners).any { isTight(literalIn(it)) }
    }

    /**
     * True if JUnit's `@Timeout` with the argument [value] and the [unit] is tight. [unit] is the name of a `TimeUnit`
     * constant, possibly qualified, or null for the default of seconds. A value or a unit that cannot be read counts as
     * generous.
     */
    fun isTightJUnitTimeout(value: String, unit: String?): Boolean {
        val amount = value.trim().removeSuffix("L").replace("_", "").toDoubleOrNull() ?: return false
        val timeUnit = unit?.substringAfterLast('.')?.trim() ?: "SECONDS"
        val durationUnit = DurationUnit.entries.firstOrNull { it.name == timeUnit } ?: return false
        return isTight(amount.toDuration(durationUnit))
    }

    /** True if [limit] is known and shorter than [GENEROUS]. */
    private fun isTight(limit: Duration?): Boolean = limit != null && limit < GENEROUS

    /** The first duration literal in [text], or null if it holds none. */
    private fun literalIn(text: String): Duration? {
        val kotlin = KOTLIN_DURATION.find(text)
        val java = JAVA_DURATION.find(text)
        return when {
            kotlin != null && (java == null || kotlin.range.first < java.range.first) ->
                number(kotlin.groupValues[1]).toDuration(KOTLIN_UNITS.getValue(kotlin.groupValues[2]))
            java != null -> number(java.groupValues[2]).toDuration(JAVA_UNITS.getValue(java.groupValues[1]))
            else -> null
        }
    }

    /** The value of a number literal with optional digit separators. */
    private fun number(literal: String): Double = literal.replace("_", "").toDouble()
}
