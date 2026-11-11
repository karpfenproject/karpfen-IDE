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
 * The rendering benchmark. It opens a KStates document of 10,000 lines from `SyntheticKstates.withMinLines(10_000)`
 * with highlighting and scrolls it to the middle. After a warm-up, every measured frame scrolls by a few lines, so new
 * lines are laid out and drawn. Each frame is rendered into an image, which also forces the draw phase. The average
 * frame time is reported through JUnit's [TestReporter] and asserted against the budget of 50 ms with 5x slack.
 *
 * The class is tagged `budget` and is excluded from every test run, because machine load and sleep cycles make
 * wall-clock limits unreliable. It is kept for later performance work.
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

    /** Holds the document size, the frame counts and the budget. */
    private companion object {
        /** The minimum number of lines of the document. */
        const val MIN_LINES = 10_000

        /** How many lines each frame scrolls. */
        const val LINES_PER_FRAME = 7

        /** Frames rendered before the measurement starts. */
        const val WARM_UP_FRAMES = 20

        /** Frames whose time is averaged. */
        const val MEASURED_FRAMES = 60

        /** The frame budget in milliseconds. */
        const val BUDGET_MILLIS = 50.0

        /** The factor by which the average may exceed the budget before the test fails. */
        const val SLACK = 5
    }
}
