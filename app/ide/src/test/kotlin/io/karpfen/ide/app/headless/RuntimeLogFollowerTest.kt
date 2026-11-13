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
package io.karpfen.ide.app.headless

import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [RuntimeLogFollower]: without verbose only the lines of the simplified log are written, in its short form,
 * with verbose every line is written as it is as well, and a line is written once although the log changes again.
 */
class RuntimeLogFollowerTest {

    private val standardOutput = ByteArrayOutputStream()
    private val console = HeadlessConsole(PrintStream(standardOutput, true, Charsets.UTF_8), PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
    private val log = RingOutputBuffer()

    @Test
    fun `without verbose only the lines of the simplified log are written in its short form, each once`() = runTest {
        val following = RuntimeLogFollower(console, verbose = false).follow(backgroundScope, log)
        log.append(TIME, OutputLevel.INFO, "stdout", "Started environment", null)
        testScheduler.runCurrent()
        log.append(TIME, OutputLevel.ERROR, "stderr", "Exception in thread main", null)
        testScheduler.runCurrent()
        following.cancel()

        assertEquals(listOf("$CLOCK  Exception in thread main"), lines())
    }

    @Test
    fun `with verbose every line is written as it is, before its short form`() = runTest {
        val following = RuntimeLogFollower(console, verbose = true).follow(backgroundScope, log)
        log.append(TIME, OutputLevel.INFO, "stdout", "Started environment", null)
        log.append(TIME, OutputLevel.ERROR, "stderr", "Exception in thread main", null)
        testScheduler.runCurrent()
        following.cancel()

        assertEquals(
            listOf("[stdout] Started environment", "[stderr] Exception in thread main", "$CLOCK  Exception in thread main"),
            lines(),
        )
    }

    /** The lines written so far. */
    private fun lines(): List<String> = standardOutput.toString(Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }

    private companion object {
        /** When every line of the tests was logged. */
        val TIME: Instant = Instant.parse("2026-11-13T08:30:00.250Z")

        /** [TIME] as the simplified log shows the time of a line without a time of its own. */
        val CLOCK: String = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(TIME)
    }
}
