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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail
import kotlin.time.Duration

/**
 * Polls [probe] every [Patience.pollEvery] until it returns a value, and returns that value. It is an eventual
 * assertion with a generous bound, for a runtime whose reactions take an unknown time. While the awaited state has not
 * come yet, the probe returns null or throws an [AssertionError], such as a failed `assertEquals`. Any other exception
 * fails at once.
 *
 * The wait uses only `delay` and `withTimeoutOrNull`, so it runs on virtual time in a test and on real time against a
 * real runtime, and measures nothing on the wall clock.
 *
 * @param what the awaited state in words, such as `the lamp's stack ["on"]`.
 * @param within the bound, [Patience.reaction] by default.
 * @throws AssertionError naming [what], the bound, the last answer of the probe and the scope's
 *   [diagnostics][RuntimeScenarioScope.diagnostics] if no value came within the bound. The probe's last
 *   [AssertionError] becomes the cause.
 */
public suspend fun <T : Any> RuntimeScenarioScope.eventually(
    what: String,
    within: Duration = patience.reaction,
    probe: suspend () -> T?,
): T {
    var last: String = "no answer yet"
    var lastFailure: AssertionError? = null
    val value = withTimeoutOrNull(within) {
        var answer: T? = null
        while (answer == null) {
            answer = try {
                probe().also { if (it == null) last = "null" }
            } catch (failure: AssertionError) {
                lastFailure = failure
                last = "${failure.message}"
                null
            }
            if (answer == null) delay(patience.pollEvery)
        }
        answer
    }
    return value ?: fail(waitFailure(what, within, "the probe's last answer: $last", diagnostics), lastFailure)
}

/**
 * Awaits the first value of [values] that [accept] accepts, and returns it. It is an eventual assertion on a flow, such
 * as the stacks a connection reports. It has the same bound and the same kind of failure as [eventually].
 *
 * The wait uses only `withTimeoutOrNull`, so it runs on virtual time in a test and on real time against a real runtime.
 *
 * @param what the awaited value in words.
 * @param within the bound, [Patience.reaction] by default.
 * @throws AssertionError naming [what], the bound, the last value the flow emitted and the scope's
 *   [diagnostics][RuntimeScenarioScope.diagnostics] if no accepted value came within the bound, or if the flow ended
 *   before one came.
 */
public suspend fun <T> RuntimeScenarioScope.awaitValue(
    what: String,
    values: Flow<T>,
    within: Duration = patience.reaction,
    accept: (T) -> Boolean,
): T {
    var last: String = "no value yet"
    val found: Awaited<T>? = withTimeoutOrNull(within) {
        var accepted: Awaited<T> = Ended
        values.firstOrNull { value ->
            last = value.toString()
            accept(value).also { if (it) accepted = Accepted(value) }
        }
        accepted
    }
    return when (found) {
        is Accepted -> found.value
        Ended -> fail(waitFailure(what, within, "the flow ended; its last value: $last", diagnostics))
        null -> fail(waitFailure(what, within, "the flow's last value: $last", diagnostics))
    }
}

/** The outcome of an [awaitValue] that did not time out. */
private sealed interface Awaited<out T>

/** The flow emitted an accepted [value]. */
private class Accepted<T>(val value: T) : Awaited<T>

/** The flow ended without an accepted value. */
private data object Ended : Awaited<Nothing>

/** The message of a wait for [what] that did not end within [within]. */
private fun waitFailure(what: String, within: Duration, last: String, diagnostics: String): String =
    "Waited $within for $what in vain; $last. $diagnostics"
