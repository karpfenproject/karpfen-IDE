package io.karpfen.ide.core.document.antlr.lex

import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The tokenize budget of spec §3.4: a 5,000-line KStates document tokenizes in < 30 ms, asserted with 5x slack; the
 * measured average after warm-up is reported through [TestReporter].
 */
class TokenizeBudgetTest {

    @Test
    fun `a 5000-line KStates document tokenizes within budget`(reporter: TestReporter) {
        val text = SyntheticKstates.withMinLines(LINES)
        val tokenizer = KstatesTokenizer()
        var version = 0L
        repeat(WARM_UP) { tokenizer.tokenize(TextSnapshot(text, version++)) }

        val mark = TimeSource.Monotonic.markNow()
        var tokens = 0
        repeat(MEASURED) { tokens = tokenizer.tokenize(TextSnapshot(text, version++)).tokens.size }
        val average = mark.elapsedNow() / MEASURED

        reporter.publishEntry(
            "tokenize ${SyntheticKstates.lineCount(text)}-line KStates",
            "measured $average ($tokens tokens), budget $BUDGET (asserted with ${SLACK}x slack)",
        )
        assertTrue(average < BUDGET * SLACK, "tokenizing took $average, budget $BUDGET x $SLACK")
    }

    private companion object {
        const val LINES = 5_000
        const val WARM_UP = 30
        const val MEASURED = 30
        const val SLACK = 5
        val BUDGET = 30.milliseconds
    }
}
