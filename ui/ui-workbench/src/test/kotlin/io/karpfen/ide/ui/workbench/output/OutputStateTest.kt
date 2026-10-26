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
package io.karpfen.ide.ui.workbench.output

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [OutputState], the state holder of the Language Tools Output, [OutputReadingPosition] and [OutputLineFormat]: the
 * state follows the buffer, counts the errors and warnings that arrive while the tool window is hidden until it is
 * shown, reads a burst of output as one snapshot, clears the buffer, keeps the follow toggle and the reading position,
 * and follows the newest lines when the tool window is shown with unseen problems.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutputStateTest {

    private val time: Instant = Instant.parse("2026-10-22T10:15:30.123Z")
    private val buffer = RingOutputBuffer()
    private val visible = MutableStateFlow(false)

    private fun log(level: OutputLevel, message: String = level.name) =
        buffer.append(time, level, "io.karpfen.dsl.dsl.textual.KmetaDSLConverter", message, null)

    private fun TestScope.boundState(): OutputState =
        OutputState(backgroundScope, buffer).also { it.bindVisibility(visible) }

    @Test
    fun `the state follows the buffer`() = runTest {
        val state = boundState()
        assertEquals(OutputUiState(), state.state.value)

        log(OutputLevel.DEBUG, "Type Robot: a robot")
        log(OutputLevel.INFO, "done")
        runCurrent()

        assertEquals(listOf("Type Robot: a robot", "done"), state.state.value.lines.map { it.message })
    }

    @Test
    fun `errors and warnings that arrive while the tool window is hidden are unseen until it is shown`() = runTest {
        val state = boundState()

        log(OutputLevel.WARNING)
        log(OutputLevel.ERROR)
        log(OutputLevel.WARNING)
        log(OutputLevel.DEBUG)
        runCurrent()
        assertEquals(UnseenOutputProblems(errors = 1, warnings = 2), state.state.value.unseen)

        visible.value = true
        runCurrent()
        assertTrue(state.state.value.unseen.isEmpty)

        log(OutputLevel.ERROR)
        runCurrent()
        assertTrue(state.state.value.unseen.isEmpty, "a visible tool window shows new problems at once")

        visible.value = false
        log(OutputLevel.WARNING)
        runCurrent()
        assertEquals(UnseenOutputProblems(warnings = 1), state.state.value.unseen)
    }

    @Test
    fun `problems cleared or dropped before anyone looked stay unseen`() = runTest {
        val small = RingOutputBuffer(capacity = 1)
        val state = OutputState(backgroundScope, small).also { it.bindVisibility(visible) }

        small.append(time, OutputLevel.ERROR, "io.karpfen.dsl.X", "lost", null)
        small.append(time, OutputLevel.DEBUG, "io.karpfen.dsl.X", "kept", null)
        runCurrent()
        assertEquals(listOf("kept"), state.state.value.lines.map { it.message })
        assertEquals(UnseenOutputProblems(errors = 1), state.state.value.unseen)

        state.clear()
        runCurrent()
        assertEquals(emptyList(), state.state.value.lines)
        assertEquals(UnseenOutputProblems(errors = 1), state.state.value.unseen)
    }

    @Test
    fun `showing the tool window with unseen problems follows the newest lines`() = runTest {
        val state = boundState()
        state.setFollow(false)
        log(OutputLevel.WARNING)
        runCurrent()
        assertFalse(state.state.value.follow, "a hidden tool window does not move")

        visible.value = true
        runCurrent()

        assertTrue(state.state.value.follow)
    }

    @Test
    fun `showing the tool window without unseen problems keeps where the user left it`() = runTest {
        val state = boundState()
        state.setFollow(false)
        state.keepReadingPosition(OutputReadingPosition(sequence = 3, offset = 12))
        log(OutputLevel.DEBUG)
        runCurrent()

        visible.value = true
        runCurrent()

        assertFalse(state.state.value.follow)
        assertEquals(OutputReadingPosition(sequence = 3, offset = 12), state.readingPosition)
    }

    @Test
    fun `a reading position is found again among the lines, or the line after it`() {
        val lines = (5L..9L).map { OutputLine(it, time, OutputLevel.DEBUG, "io.karpfen.dsl.X", "line $it") }

        val kept = OutputReadingPosition(sequence = 7, offset = 12)
        val dropped = OutputReadingPosition(sequence = 3, offset = 12)

        assertEquals(2 to 12, kept.indexIn(lines) to kept.offsetIn(lines))
        assertEquals(0 to 0, dropped.indexIn(lines) to dropped.offsetIn(lines))
        assertEquals(0 to 0, kept.indexIn(emptyList()) to kept.offsetIn(emptyList()))
    }

    @Test
    fun `a burst of output is read as one snapshot`() = runTest {
        val reads = CountingBuffer(buffer)
        val state = OutputState(backgroundScope, reads).also { it.bindVisibility(visible) }
        runCurrent()
        val before = reads.snapshots

        repeat(100) { log(OutputLevel.DEBUG, "line $it") }
        runCurrent()

        assertEquals(1, reads.snapshots - before)
        assertEquals(100, state.state.value.lines.size)
    }

    @Test
    fun `clear empties the buffer and follow is a toggle of the view`() = runTest {
        val state = boundState()
        log(OutputLevel.INFO)
        runCurrent()

        state.clear()
        state.setFollow(false)
        runCurrent()

        assertEquals(emptyList(), buffer.snapshot().lines)
        assertEquals(emptyList(), state.state.value.lines)
        assertFalse(state.state.value.follow)
        state.setFollow(true)
        assertTrue(state.state.value.follow)
    }

    @Test
    fun `a line is written with its local time, padded level, the logger's simple name and its file`() {
        val format = OutputLineFormat(ZoneOffset.ofHours(2))
        val line = OutputLine(7, time, OutputLevel.WARNING, "io.karpfen.dsl.parser.KmetaFileParser", "KMeta syntax error")

        assertEquals("12:15:30.123", format.time(line))
        assertEquals("WARN ", format.level(line))
        assertEquals("ERROR", format.level(line.copy(level = OutputLevel.ERROR)))
        assertEquals("KmetaFileParser", format.logger(line))
        assertEquals("root", format.logger(line.copy(logger = "root")))
        assertEquals(null, format.file(line))
        assertEquals("[/work/robot.kmeta]", format.file(line.copy(file = "/work/robot.kmeta")))
    }

    /** An [OutputBuffer] over [buffer] that counts the snapshots read. */
    private class CountingBuffer(private val buffer: OutputBuffer) : OutputBuffer by buffer {
        /** How many snapshots were read. */
        var snapshots = 0
            private set

        override fun snapshot(): OutputSnapshot {
            snapshots++
            return buffer.snapshot()
        }
    }
}
