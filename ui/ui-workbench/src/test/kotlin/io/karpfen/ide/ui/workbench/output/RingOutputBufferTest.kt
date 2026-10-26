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

import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * [RingOutputBuffer]: lines numbered in arrival order, the oldest dropped beyond the capacity, problem counts that
 * survive dropping and clearing, a revision for every change, snapshots that are copies, and appends from many threads.
 */
class RingOutputBufferTest {

    private val time: Instant = Instant.parse("2026-10-22T10:15:30.123Z")

    private fun RingOutputBuffer.log(level: OutputLevel, message: String) = append(time, level, "io.karpfen.dsl.Test", message)

    @Test
    fun `lines are numbered from 1 in arrival order and keep what was logged`() {
        val buffer = RingOutputBuffer()

        buffer.log(OutputLevel.DEBUG, "first")
        buffer.append(time, OutputLevel.WARNING, "io.karpfen.dsl.parser.KmetaFileParser", "second")

        assertEquals(
            listOf(
                OutputLine(1, time, OutputLevel.DEBUG, "io.karpfen.dsl.Test", "first"),
                OutputLine(2, time, OutputLevel.WARNING, "io.karpfen.dsl.parser.KmetaFileParser", "second"),
            ),
            buffer.snapshot().lines,
        )
    }

    @Test
    fun `beyond its capacity the buffer drops the oldest lines`() {
        val buffer = RingOutputBuffer(capacity = 3)

        (1..5).forEach { buffer.log(OutputLevel.INFO, "line $it") }

        assertEquals(listOf("line 3", "line 4", "line 5"), buffer.snapshot().lines.map { it.message })
        assertEquals(listOf(3L, 4L, 5L), buffer.snapshot().lines.map { it.sequence })
    }

    @Test
    fun `errors and warnings are counted also after they were dropped or cleared`() {
        val buffer = RingOutputBuffer(capacity = 2)
        buffer.log(OutputLevel.ERROR, "e1")
        buffer.log(OutputLevel.WARNING, "w1")
        buffer.log(OutputLevel.DEBUG, "d1")
        buffer.log(OutputLevel.INFO, "i1")

        assertEquals(1, buffer.snapshot().appendedErrors)
        assertEquals(1, buffer.snapshot().appendedWarnings)

        buffer.clear()
        buffer.log(OutputLevel.WARNING, "w2")

        val snapshot = buffer.snapshot()
        assertEquals(listOf(OutputLine(5, time, OutputLevel.WARNING, "io.karpfen.dsl.Test", "w2")), snapshot.lines)
        assertEquals(1, snapshot.appendedErrors)
        assertEquals(2, snapshot.appendedWarnings)
    }

    @Test
    fun `every append and every clear changes the revision`() {
        val buffer = RingOutputBuffer()
        val revisions = mutableListOf(buffer.revision.value)

        buffer.log(OutputLevel.INFO, "a")
        revisions += buffer.revision.value
        buffer.clear()
        revisions += buffer.revision.value
        buffer.log(OutputLevel.INFO, "b")
        revisions += buffer.revision.value

        assertEquals(revisions.distinct(), revisions)
        assertEquals(OutputSnapshot.EMPTY, RingOutputBuffer().snapshot())
    }

    @Test
    fun `a snapshot is a copy that later appends do not change`() {
        val buffer = RingOutputBuffer()
        buffer.log(OutputLevel.INFO, "a")
        val before = buffer.snapshot()

        buffer.log(OutputLevel.INFO, "b")
        buffer.clear()

        assertEquals(listOf("a"), before.lines.map { it.message })
        assertNotEquals(before, buffer.snapshot())
    }

    @Test
    fun `appends from many threads lose no line and share out every sequence number once`() {
        val buffer = RingOutputBuffer(capacity = THREADS * APPENDS_PER_THREAD)
        val start = CountDownLatch(1)
        val threads = List(THREADS) { index ->
            thread {
                start.await()
                repeat(APPENDS_PER_THREAD) { buffer.log(if (it % 2 == 0) OutputLevel.WARNING else OutputLevel.DEBUG, "$index/$it") }
            }
        }

        start.countDown()
        threads.forEach(Thread::join)

        val snapshot = buffer.snapshot()
        assertEquals((1L..THREADS * APPENDS_PER_THREAD).toList(), snapshot.lines.map { it.sequence })
        assertEquals(THREADS * APPENDS_PER_THREAD, snapshot.lines.map { it.message }.toSet().size)
        assertEquals(THREADS * APPENDS_PER_THREAD / 2L, snapshot.appendedWarnings)
    }

    @Test
    fun `the capacity must be positive`() {
        assertFailsWith<IllegalArgumentException> { RingOutputBuffer(capacity = 0) }
    }

    private companion object {
        /** How many threads append at once. */
        const val THREADS = 8

        /** How many lines each thread appends. */
        const val APPENDS_PER_THREAD = 500
    }
}
