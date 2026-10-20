package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.text.TextSnapshot
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The parse budget of spec §3.4 (`DocumentParser`): a 5,000-line KStates document parses (syntax only, the default
 * mode) in < 150 ms, asserted with 5x slack; the measured value is reported through [TestReporter].
 */
class ParseBudgetTest {

    @Test
    fun `a 5000-line KStates document parses within the budget`(reporter: TestReporter) {
        val text = SyntheticKstates.withMinLines(LINES)
        val parser: DocumentParser = ComposedDocumentParser(KarpfenLanguage.KSTATES, KstatesSyntaxAnalyzer(), semantics = null)
        val snapshots = List(WARM_UP + MEASURED) { TextSnapshot(text, it.toLong()) }
        snapshots.take(WARM_UP).forEach { check(parser.parse(it).isSyntaxClean) }

        val mark = TimeSource.Monotonic.markNow()
        snapshots.drop(WARM_UP).forEach { parser.parse(it) }
        val measured: Duration = mark.elapsedNow() / MEASURED

        val lines = SyntheticKstates.lineCount(text)
        reporter.publishEntry("parse of $lines KStates lines", "measured $measured, budget $BUDGET (asserted with ${SLACK}x slack)")
        assertTrue(measured < BUDGET * SLACK, "parsing $lines lines took $measured, budget $BUDGET x $SLACK")
    }

    private companion object {
        const val LINES = 5_000
        const val WARM_UP = 10
        const val MEASURED = 20
        const val SLACK = 5
        val BUDGET = 150.milliseconds
    }
}
