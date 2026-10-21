package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rendering benchmark of spec §7.2: a 10,000-line KStates document (`SyntheticKstates.withMinLines(10_000)`) with
 * highlighting, scrolled to the middle; after a warm-up, every measured frame scrolls by a few lines (so new lines are
 * laid out and drawn) and is rendered into an image, which also forces the draw phase. The average frame time is
 * reported through JUnit's [TestReporter] and asserted against the budget of 50 ms with the plan's 5x slack.
 *
 * Tagged `budget`: excluded from every test run (docs/adr/0011-time-budget-tests-are-not-run.md); kept for later
 * performance work.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("budget")
class CodeEditorBenchmarkTest {

    @Test
    fun `a 10,000-line document scrolled to the middle renders within the frame budget`(reporter: TestReporter) = runComposeUiTest {
        val text = SyntheticKstates.withMinLines(MIN_LINES)
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(text, width = 1200.dp, height = 800.dp)
        waitUntil(timeoutMillis = 30_000) { ui.state.highlights.value.spans.isNotEmpty() }
        val snapshot = ui.text.snapshot.value
        ui.state.scrollTo(snapshot.lineIndex.lineStart(snapshot.lineCount / 2), center = true)
        waitForIdle()
        val lineHeight = ui.state.viewport.state.value.lineHeight
        assertTrue(ui.state.viewport.state.value.scrollY > snapshot.lineCount / 3 * lineHeight, "scrolled to the middle")

        fun frame() {
            ui.state.viewport.scrollBy(0f, lineHeight * LINES_PER_FRAME)
            ui.textArea.captureToImage()
        }
        repeat(WARM_UP_FRAMES) { frame() }
        val start = System.nanoTime()
        repeat(MEASURED_FRAMES) { frame() }
        val averageMillis = (System.nanoTime() - start) / 1e6 / MEASURED_FRAMES

        reporter.publishEntry("lines", snapshot.lineCount.toString())
        reporter.publishEntry("averageFrameMillis", "%.2f".format(Locale.ROOT, averageMillis))
        reporter.publishEntry("budgetMillis", BUDGET_MILLIS.toString())
        assertTrue(averageMillis < BUDGET_MILLIS * SLACK, "average frame time $averageMillis ms (budget $BUDGET_MILLIS ms, 5x slack)")
    }

    private companion object {
        const val MIN_LINES = 10_000
        const val LINES_PER_FRAME = 7
        const val WARM_UP_FRAMES = 20
        const val MEASURED_FRAMES = 60
        const val BUDGET_MILLIS = 50.0
        const val SLACK = 5
    }
}
