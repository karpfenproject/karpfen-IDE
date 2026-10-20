package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Find on a large document stays interactive: matching and expanding replacements over a 5,000-line KStates text.
 * The editor model has no budget in the spec; these are the model's own targets (incremental search recomputes on
 * every keystroke in the find field), asserted with 5x slack and reported through [TestReporter].
 */
class FindBudgetTest {

    private val text = SyntheticKstates.withMinLines(5000)
    private val matcher = FindMatcher()

    private fun average(runs: Int, block: () -> Unit): Duration {
        repeat(3) { block() }
        return measureTime { repeat(runs) { block() } } / runs
    }

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
