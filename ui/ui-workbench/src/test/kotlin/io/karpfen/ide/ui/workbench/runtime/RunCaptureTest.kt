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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.host.LogStream
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the capture of a run's log to a file: the name after the start of the run with a number added on a clash, the
 * header, every line in order and unfiltered with the mark of its stream, the environment line, the end of the capture,
 * the note of a runtime without a log, and the problems of a folder or a file that cannot be written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunCaptureTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val folder: Path get() = root.resolve(WorkspaceOptions.RUN_CAPTURE_DIRECTORY)
    private val log = ListeningLog()
    private val problems = ArrayList<String>()

    @Test
    fun `the file is named after the start and holds the header and then every line in order, unfiltered`() = runTest {
        val capture = assertNotNull(start(RuntimeHostKind.DOCKER))

        log.emit(HostLogLine("Deploying lamp"))
        log.emit(HostLogLine(NOISE))
        log.emit(HostLogLine("[12:41:30.130] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on"))
        capture.environment(EnvironmentKey("env-1"))
        capture.environment(EnvironmentKey("env-1"))
        log.emit(HostLogLine("Exception in thread main", LogStream.STDERR))
        advanceUntilIdle()

        assertEquals(folder.resolve("2026-11-13_14-32-05.log"), capture.file)
        assertEquals(
            listOf(
                "# karpfen run capture",
                "# Descriptor: lamp/lamp.kenv.json",
                "# Started: 2026-11-13 14:32:05",
                "# Runtime: the IDE's Docker container at http://127.0.0.1:8080",
                "stdout Deploying lamp",
                "stdout $NOISE",
                "stdout [12:41:30.130] [env-1] [lamp] TRANSITION_FIRED: off -> on | from=off, to=on",
                "# Environment: env-1",
                "stderr Exception in thread main",
            ),
            Files.readAllLines(capture.file),
        )
        assertTrue(EngineTrace.isNoise(NOISE), "the Log pane leaves this line out")
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `lines after the end of the capture are not written, and the file stays as it was`() = runTest {
        val capture = assertNotNull(start(RuntimeHostKind.DOCKER))
        log.emit(HostLogLine("before"))

        capture.close()
        capture.close()
        log.emit(HostLogLine("after"))
        advanceUntilIdle()

        assertEquals("stdout before", Files.readAllLines(capture.file).last())
        assertEquals(0, log.listeners.size, "the capture no longer listens")
    }

    @Test
    fun `a taken name gets a number`() = runTest {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("2026-11-13_14-32-05.log"), "an earlier run")

        val second = assertNotNull(start(RuntimeHostKind.DOCKER))
        val third = assertNotNull(start(RuntimeHostKind.DOCKER))
        advanceUntilIdle()

        assertEquals("2026-11-13_14-32-05-2.log", second.file.fileName.toString())
        assertEquals("2026-11-13_14-32-05-3.log", third.file.fileName.toString())
        assertEquals("an earlier run", Files.readString(folder.resolve("2026-11-13_14-32-05.log")))
    }

    @Test
    fun `a runtime attached by URL gives no log, and the file says so after the header`() = runTest {
        val capture = assertNotNull(start(RuntimeHostKind.URL))
        advanceUntilIdle()

        assertEquals(
            listOf("# Runtime: attached by URL at http://127.0.0.1:8080", RunCaptures.NO_LOG),
            Files.readAllLines(capture.file).takeLast(2),
        )
    }

    @Test
    fun `a folder that cannot be created starts no capture and tells the problem once`() = runTest {
        Files.createDirectories(root)
        Files.writeString(folder, "a file where the folder belongs")

        val capture = start(RuntimeHostKind.DOCKER)

        assertNull(capture)
        assertEquals(1, problems.size, "$problems")
        assertTrue(problems.single().isNotBlank())
        assertEquals(0, log.listeners.size)
    }

    @Test
    fun `a file that cannot be written tells the problem once and the capture ends quietly`() = runTest {
        val writer = FailingWriter()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        RunCapture(folder.resolve("x.log"), writer, log, scope, StandardTestDispatcher(testScheduler), problems::add)

        log.emit(HostLogLine("one"))
        advanceUntilIdle()
        log.emit(HostLogLine("two"))
        advanceUntilIdle()

        assertEquals(listOf("IOException: the disk is full"), problems)

        scope.cancel()
        advanceUntilIdle()

        assertTrue(writer.closed, "the file is closed when the scope ends")
    }

    /**
     * Starts the capture of the lamp's descriptor on the runtime of [host], in a scope on the test's scheduler. The
     * scope is not the test's background scope, so `advanceUntilIdle` writes the lines.
     */
    private suspend fun TestScope.start(host: RuntimeHostKind): RunCapture? {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val captures = RunCaptures(CoroutineScope(dispatcher + Job()), root, log, dispatcher, CLOCK)
        return captures.start(root.resolve("lamp/lamp.kenv.json"), CLOCK.instant(), host, ENDPOINT, problems::add)
    }

    /** A log whose lines the test emits to the listeners. */
    private class ListeningLog : RuntimeLogSource {
        /** The listeners that are registered now. */
        val listeners = ArrayList<(HostLogLine) -> Unit>()

        override fun observe(listener: (HostLogLine) -> Unit): DisposableHandle {
            listeners.add(listener)
            return DisposableHandle { listeners.remove(listener) }
        }

        /** Hands [line] to every listener. */
        fun emit(line: HostLogLine) {
            listeners.toList().forEach { it(line) }
        }
    }

    /** A writer whose every write fails as on a full disk. It records that it was closed. */
    private class FailingWriter : Writer() {
        /** True once the writer was closed. */
        var closed = false
            private set

        override fun write(cbuf: CharArray, off: Int, len: Int): Unit = throw IOException("the disk is full")

        override fun flush(): Unit = throw IOException("the disk is full")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        /** The clock of the run's start. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-13T14:32:05Z"), ZoneOffset.UTC)

        /** Where the runtime is reached. */
        val ENDPOINT: RuntimeEndpoint = RuntimeEndpoint.parse("http://127.0.0.1:8080")

        /** A line the engine trace writes on every tick, which the Log pane leaves out. */
        const val NOISE = "[12:41:30.123] [env-1] [lamp] TICK_START: tick 3"
    }
}
