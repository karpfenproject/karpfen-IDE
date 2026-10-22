package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.FindBarMode
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestReporter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Painting find matches costs what the visible matches cost: with the find bar open over a long line full of matches, a
 * frame takes about as long as with the bar closed. Painting used to compute every match's x from the start of its line
 * (O(matches × line length) per frame: a 40,000-character line with 8,000 matches cost many times a frame without
 * find).
 *
 * Tagged `budget`: excluded from every test run, because machine load and sleep cycles make wall-clock limits
 * unreliable; kept for later performance work.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("budget")
class FindMatchPaintingBenchmarkTest {

    @Test
    fun `a long line full of find matches paints about as fast as without find`(reporter: TestReporter) = runComposeUiTest {
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(TEXT, width = 1200.dp, height = 800.dp)
        runOnIdle {
            ui.state.find.setQuery(FindQuery("\"", caseSensitive = true))
            ui.state.overlays.openFind(FindBarMode.FIND)
        }
        waitForIdle()
        assertEquals(MATCHES, ui.state.find.state.value.total)

        val open = averageFrameMillis(ui)
        runOnIdle { ui.state.overlays.closeFind() }
        waitForIdle()
        val closed = averageFrameMillis(ui)

        reporter.publishEntry("findOpenFrameMillis", "%.2f".format(Locale.ROOT, open))
        reporter.publishEntry("findClosedFrameMillis", "%.2f".format(Locale.ROOT, closed))
        assertTrue(open < closed * FACTOR + SLACK_MILLIS, "find open: $open ms per frame, closed: $closed ms")
    }

    /** Frames that move the caret on the first line and render the text area, averaged after a warm-up. */
    private fun ComposeUiTest.averageFrameMillis(ui: EditorUi): Double {
        var column = 0
        fun frame() {
            column = (column + 1) % 10
            runOnIdle { ui.state.caret.moveTo(column) }
            ui.textArea.captureToImage()
        }
        repeat(WARM_UP_FRAMES) { frame() }
        val start = System.nanoTime()
        repeat(MEASURED_FRAMES) { frame() }
        return (System.nanoTime() - start) / 1e6 / MEASURED_FRAMES
    }

    /** The text, the frames and the allowed difference. */
    private companion object {
        const val MATCHES = 8_000
        const val WARM_UP_FRAMES = 5
        const val MEASURED_FRAMES = 20
        const val FACTOR = 3
        const val SLACK_MILLIS = 10.0

        /** 20 short lines and one comment line of 40,003 characters with [MATCHES] quotes. */
        val TEXT: String = (0 until 20).joinToString("\n") { "    // line $it" } + "\n// " + "ab\"cd".repeat(MATCHES) + "\n}"
    }
}
