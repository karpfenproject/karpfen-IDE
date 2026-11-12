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
package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Find on a large document stays interactive. The tests time matching and the expansion of replacements over a KStates
 * text of 5,000 lines. The limits are the model's own targets, because incremental search runs again on every keystroke
 * in the find field. They are asserted with 5x slack and reported through [TestReporter].
 *
 * The class is tagged `budget` and is excluded from every test run, because machine load and sleep cycles make
 * wall-clock limits unreliable. It documents the performance targets.
 */
@Tag("budget")
class FindBudgetTest {

    /** A synthetic KStates text of at least 5,000 lines. */
    private val text = SyntheticKstates.withMinLines(5000)

    /** The matcher under test. */
    private val matcher = FindMatcher()

    /** The mean duration of [block] over [runs] runs, measured after three warm-up runs. */
    private fun average(runs: Int, block: () -> Unit): Duration {
        repeat(3) { block() }
        return measureTime { repeat(runs) { block() } } / runs
    }

    /** Reports [measured] against [budget] and asserts that it stays below five times the budget. */
    private fun check(reporter: TestReporter, name: String, measured: Duration, budget: Duration) {
        reporter.publishEntry(name, "measured ${measured.inWholeMicroseconds} us, budget ${budget.inWholeMilliseconds} ms (asserted x5)")
        assertTrue(measured < budget * 5, "$name took $measured, budget $budget (x5 slack)")
    }

    @Test
    fun `plain and regex search over 5000 lines`(reporter: TestReporter) {
        check(reporter, "find plain 'state' in 5000 lines", average(10) { matcher.find(text, FindQuery("state")) }, 20.milliseconds)
        check(
            reporter, "find regex whole-word in 5000 lines",
            average(10) { matcher.find(text, FindQuery("s\\d+", regex = true, wholeWord = true)) }, 30.milliseconds,
        )
    }

    @Test
    fun `replacement expansion for every match of 5000 lines`(reporter: TestReporter) {
        val query = FindQuery("(s)(\\d+)", regex = true)
        val matches = matcher.find(text, query).matches
        assertTrue(matches.size > 500, "a realistic number of matches: ${matches.size}")
        check(reporter, "expand ${matches.size} replacements", average(5) { matcher.replacements(text, query, matches, "$2$1") }, 30.milliseconds)
    }
}
