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
package io.karpfen.ide.ui.workbench.output.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.OutputLine
import io.karpfen.ide.ui.workbench.output.OutputReadingPosition
import io.karpfen.ide.ui.workbench.output.OutputUiState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [OutputView] headless: each line shows its level, the logger's simple name and the message; while following, the
 * newest line is scrolled into sight when it arrives, and without following the view stays where it is; following
 * yields to the user, who stops it by scrolling or dragging the scrollbar away from the newest line, keeps it by
 * scrolling within a newest line taller than the view, and resumes it by scrolling back to the end; Follow and Clear
 * report to their callbacks, and Clear is disabled without output.
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

    /**
     * Shows the view over [initial] in a small box with Follow switched as the view asks, the way the workbench's
     * output state switches it; returns the state, which the test changes as output arrives.
     */
    private fun ComposeUiTest.followingView(initial: OutputUiState): MutableState<OutputUiState> {
        val state = mutableStateOf(initial)
        setContent {
            KarpfenTheme(darkTheme = false) {
                Box(Modifier.size(500.dp, 200.dp)) {
                    OutputView(state.value, onFollowChange = { state.value = state.value.copy(follow = it) }, onClear = {})
                }
            }
        }
        return state
    }

    /** Appends the line with the next sequence number to the output of [state]. */
    private fun MutableState<OutputUiState>.append() {
        value = value.copy(lines = value.lines + line(value.lines.size + 1L))
    }

    @Test
    fun `a line shows its level, the logger's simple name and the message`() = runComposeUiTest {
        view(OutputUiState(lines = listOf(line(1, OutputLevel.WARNING, "KMeta syntax error at line 1:5"))))

        onNodeWithTag(OutputViewTestTags.line(1))
            .assertTextContains("WARN", substring = true)
            .assertTextContains("KmetaDSLConverter - KMeta syntax error at line 1:5", substring = true)
    }

    @Test
    fun `a line logged while a file was read names the file`() = runComposeUiTest {
        view(OutputUiState(lines = listOf(line(1, OutputLevel.WARNING, "failed").copy(file = "/work/robot/robot.kstates"))))

        onNodeWithTag(OutputViewTestTags.line(1))
            .assertTextContains("KmetaDSLConverter [/work/robot/robot.kstates] - failed", substring = true)
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
    fun `scrolling away from the newest line stops following, so arriving lines leave the view where it is`() =
        runComposeUiTest {
            val state = followingView(OutputUiState(lines = (1L..30L).map { line(it) }))
            waitForIdle()
            onNodeWithTag(OutputViewTestTags.line(30)).assertIsDisplayed()

            repeat(5) { onNodeWithTag(OutputViewTestTags.LINES).performTouchInput { swipeDown() } }
            waitForIdle()
            assertFalse(state.value.follow, "scrolling towards older lines switched following off")
            onNodeWithTag(OutputViewTestTags.line(1)).assertIsDisplayed()

            state.append()
            waitForIdle()

            onNodeWithTag(OutputViewTestTags.line(1)).assertIsDisplayed()
            onNodeWithTag(OutputViewTestTags.line(31)).assertDoesNotExist()
        }

    @Test
    fun `scrolling back to the newest line follows again`() = runComposeUiTest {
        val state = followingView(OutputUiState(lines = (1L..30L).map { line(it) }, follow = false))
        waitForIdle()
        onNodeWithTag(OutputViewTestTags.line(1)).assertIsDisplayed()

        repeat(5) { onNodeWithTag(OutputViewTestTags.LINES).performTouchInput { swipeUp() } }
        waitForIdle()
        assertTrue(state.value.follow, "reaching the newest line switched following on")

        state.append()
        waitForIdle()

        onNodeWithTag(OutputViewTestTags.line(31)).assertIsDisplayed()
    }

    /**
     * While following, the view shows the start of the newest line; when that line, such as an error with its stack
     * trace, is taller than the view, scrolling down through it keeps the newest line in sight and following on.
     */
    @Test
    fun `scrolling down within a newest line taller than the view keeps following`() = runComposeUiTest {
        val trace = (listOf("boom") + List(40) { "    at io.karpfen.Frame.call$it" }).joinToString("\n")
        val state = followingView(OutputUiState(lines = (1L..10L).map { line(it) } + line(11, OutputLevel.ERROR, trace)))
        waitForIdle()
        onNodeWithTag(OutputViewTestTags.line(11)).assertIsDisplayed()

        onNodeWithTag(OutputViewTestTags.LINES).performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        waitForIdle()
        assertTrue(state.value.follow, "the newest line is still in sight")

        state.append()
        waitForIdle()

        onNodeWithTag(OutputViewTestTags.line(12)).assertIsDisplayed()
    }

    @Test
    fun `dragging the scrollbar away from the newest line stops following too`() = runComposeUiTest {
        val state = followingView(OutputUiState(lines = (1L..200L).map { line(it) }))
        waitForIdle()
        onNodeWithTag(OutputViewTestTags.line(200)).assertIsDisplayed()

        onNodeWithTag(OutputViewTestTags.SCROLLBAR).performMouseInput {
            moveTo(bottomCenter - Offset(0f, 3f))
            press()
            moveTo(topCenter)
            release()
        }
        waitForIdle()

        assertFalse(state.value.follow, "the scrollbar switched following off")
        onNodeWithTag(OutputViewTestTags.line(1)).assertIsDisplayed()
    }

    /**
     * Hiding the tool window takes the view out of the composition, and showing it puts it back: it opens at the line
     * where the user left it, not at the oldest line.
     */
    @Test
    fun `hiding and showing the view keeps the line the user reads`() = runComposeUiTest {
        val state = mutableStateOf(OutputUiState(lines = (1L..200L).map { line(it) }))
        var shown by mutableStateOf(true)
        var position: OutputReadingPosition? = null
        setContent {
            KarpfenTheme(darkTheme = false) {
                Box(Modifier.size(500.dp, 200.dp)) {
                    if (shown) {
                        OutputView(
                            state.value,
                            onFollowChange = { state.value = state.value.copy(follow = it) },
                            onClear = {},
                            readingPosition = position,
                            onReadingPositionChange = { position = it },
                        )
                    }
                }
            }
        }
        waitForIdle()
        repeat(2) { onNodeWithTag(OutputViewTestTags.LINES).performTouchInput { swipeDown() } }
        waitForIdle()
        assertFalse(state.value.follow, "scrolling up switched following off")
        val left = checkNotNull(position) { "the view reports where the user reads" }

        shown = false
        waitForIdle()
        shown = true
        waitForIdle()

        onNodeWithTag(OutputViewTestTags.line(left.sequence)).assertIsDisplayed()
        onNodeWithTag(OutputViewTestTags.line(1)).assertDoesNotExist()
        assertEquals(left, position)
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
