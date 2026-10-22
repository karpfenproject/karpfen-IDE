package io.karpfen.ide.ui.workbench.output.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.OutputUiState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [OutputView] headless: each line shows its level, the logger's simple name and the message; while following, the
 * newest line is scrolled into sight when it arrives, and without following the view stays where it is; Follow and
 * Clear report to their callbacks, and Clear is disabled without output.
 */
@OptIn(ExperimentalTestApi::class)
class OutputViewUiTest {

    private val time: Instant = Instant.parse("2026-10-22T10:15:30.123Z")

    private fun line(sequence: Long, level: OutputLevel = OutputLevel.DEBUG, message: String = "line $sequence") =
        OutputLine(sequence, time, level, "io.karpfen.dsl.dsl.textual.KmetaDSLConverter", message)

    /** The calls of the view's callbacks. */
    private class Calls {
        /** The values passed to the follow callback. */
        val follow = mutableListOf<Boolean>()

        /** How often Clear was clicked. */
        var clears = 0
    }

    /** Shows the view over [initial] in a small box; returns the state holder for changes and the recorded calls. */
    private fun ComposeUiTest.view(initial: OutputUiState): Pair<(OutputUiState) -> Unit, Calls> {
        var state by mutableStateOf(initial)
        val calls = Calls()
        setContent {
            KarpfenTheme(darkTheme = false) {
                Box(Modifier.size(500.dp, 200.dp)) {
                    OutputView(state, onFollowChange = { calls.follow += it }, onClear = { calls.clears++ })
                }
            }
        }
        return Pair({ next -> state = next }, calls)
    }

    @Test
    fun `a line shows its level, the logger's simple name and the message`() = runComposeUiTest {
        view(OutputUiState(lines = listOf(line(1, OutputLevel.WARNING, "KMeta syntax error at line 1:5"))))

        onNodeWithTag(OutputViewTestTags.line(1))
            .assertTextContains("WARN", substring = true)
            .assertTextContains("KmetaDSLConverter - KMeta syntax error at line 1:5", substring = true)
    }

    @Test
    fun `without output the view says so and Clear is disabled`() = runComposeUiTest {
        view(OutputUiState())

        onNodeWithText("No output from the language tools").assertIsDisplayed()
        onNodeWithTag(OutputViewTestTags.CLEAR).assertIsNotEnabled()
    }

    @Test
    fun `while following, the newest line is scrolled into sight as it arrives`() = runComposeUiTest {
        val (show, _) = view(OutputUiState(lines = listOf(line(1))))

        show(OutputUiState(lines = (1L..200L).map { line(it) }))
        waitForIdle()

        onNodeWithTag(OutputViewTestTags.line(200)).assertIsDisplayed()
        onNodeWithTag(OutputViewTestTags.line(1)).assertDoesNotExist()
    }

    @Test
    fun `without following, the view stays where it is`() = runComposeUiTest {
        val (show, _) = view(OutputUiState(lines = listOf(line(1)), follow = false))

        show(OutputUiState(lines = (1L..200L).map { line(it) }, follow = false))
        waitForIdle()

        onNodeWithTag(OutputViewTestTags.line(1)).assertIsDisplayed()
        onNodeWithTag(OutputViewTestTags.line(200)).assertDoesNotExist()
    }

    @Test
    fun `Follow and Clear report to their callbacks`() = runComposeUiTest {
        val (_, calls) = view(OutputUiState(lines = listOf(line(1)), follow = true))

        onNodeWithTag(OutputViewTestTags.FOLLOW).performClick()
        onNodeWithTag(OutputViewTestTags.CLEAR).performClick()

        assertEquals(listOf(false), calls.follow)
        assertEquals(1, calls.clears)
    }
}
