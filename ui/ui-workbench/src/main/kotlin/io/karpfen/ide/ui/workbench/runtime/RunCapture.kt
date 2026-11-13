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

import io.karpfen.ide.runtime.host.HostLogLine
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Writer
import java.nio.file.Path

/**
 * The capture of one run: a file that receives every line of the runtime's log while the run lasts. [RunCaptures]
 * creates the file, writes its header and hands the open [writer] over.
 *
 * From then on the capture takes every line that [source] delivers, unfiltered, and writes it with the mark of its
 * stream in front, such as `stdout [12:41:30.123] [env-1] [lamp] TICK_START: tick 3`. The lines wait in a queue and are
 * written in the order they came, on [ioDispatcher]. After each batch of waiting lines the writer is flushed, so a
 * killed IDE leaves a file that holds every line written up to then.
 *
 * A line that cannot be written stops the writing. [onProblem] is told why, once, and later lines are dropped. The
 * run itself goes on. [close] ends the capture: no line is taken any more, the waiting lines are written, and the file
 * is closed. The file is also closed when [scope] ends. Use the class from the UI thread only.
 *
 * @property file the absolute path of the file.
 * @param writer the open writer of [file], positioned after the header.
 * @param source where the runtime's log lines arrive.
 * @param scope where the lines are written. Its dispatcher calls [onProblem].
 * @param ioDispatcher where the file is written, which blocks.
 * @param onProblem told once why the file could not be written.
 */
internal class RunCapture(
    val file: Path,
    private val writer: Writer,
    source: RuntimeLogSource,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onProblem: (String) -> Unit,
) {
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val listening = source.observe(::take)
    private var environmentNoted = false

    init {
        scope.launch { drain() }
    }

    /** Notes the key of the run's environment once it is known. Only the first call writes a line. */
    fun environment(key: EnvironmentKey) {
        if (environmentNoted) return
        environmentNoted = true
        lines.trySend("${RunCaptures.ENVIRONMENT}${key.value}")
    }

    /**
     * Ends the capture. The lines taken so far are still written, and then the file is closed. Calling it again does
     * nothing.
     */
    fun close() {
        listening.dispose()
        lines.close()
    }

    /** Queues [line] with the mark of its stream. */
    private fun take(line: HostLogLine) {
        lines.trySend("${line.stream.name.lowercase()} ${line.text}")
    }

    /**
     * Writes the queued lines in batches and flushes after each batch until the queue is closed and empty. Then, or
     * when the scope ends, the file is closed.
     */
    private suspend fun drain() {
        var failed = false
        try {
            for (first in lines) {
                val batch = mutableListOf(first)
                while (true) batch += lines.tryReceive().getOrNull() ?: break
                if (failed) continue
                try {
                    withContext(ioDispatcher) { write(batch) }
                } catch (e: IOException) {
                    failed = true
                    onProblem(RunCaptures.reason(e))
                }
            }
        } finally {
            withContext(NonCancellable + ioDispatcher) { runCatching { writer.close() } }
        }
    }

    /** Writes [batch], one line each, and flushes. Blocks. */
    private fun write(batch: List<String>) {
        batch.forEach { writer.write(it + System.lineSeparator()) }
        writer.flush()
    }
}
