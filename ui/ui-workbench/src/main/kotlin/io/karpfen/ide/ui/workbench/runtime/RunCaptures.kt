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
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Creates the captures of the runs of the workspace at [root]. Each capture is a file in the folder
 * [WorkspaceOptions.RUN_CAPTURE_DIRECTORY] of the workspace, which the workspace never scans. The file is named after
 * the local date and time the run started, such as `2026-11-13_14-32-05.log`. If that name is taken, `-2`, `-3` and so
 * on are added before the suffix.
 *
 * The file starts with a header of lines that begin with `#`. They name the descriptor relative to [root], the date
 * and time the run started, and the address of the runtime in the IDE's Docker container. The line with the environment
 * key follows later, once the run knows it.
 *
 * The files are created on [ioDispatcher]. Use the class from the UI thread only.
 *
 * @param scope where the captures write their lines.
 * @param source where the runtime's log lines arrive.
 * @param clock the time zone that names the files and dates the header.
 */
internal class RunCaptures(
    private val scope: CoroutineScope,
    private val root: Path,
    private val source: RuntimeLogSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val folder: Path = root.resolve(WorkspaceOptions.RUN_CAPTURE_DIRECTORY)

    /**
     * Starts the capture of a run of [descriptor] that started at [started] and reaches its runtime at [endpoint].
     * Returns the capture, or null if its file could not be created, after telling [onProblem] why.
     * [onProblem] is also told, once, if writing the file fails later.
     */
    suspend fun start(
        descriptor: Path,
        started: Instant,
        endpoint: RuntimeEndpoint,
        onProblem: (String) -> Unit,
    ): RunCapture? {
        val time = LocalDateTime.ofInstant(started, clock.zone)
        val header = header(descriptor, time, endpoint)
        return try {
            val (file, writer) = withContext(ioDispatcher) { create(NAME.format(time), header) }
            RunCapture(file, writer, source, scope, ioDispatcher, onProblem)
        } catch (e: IOException) {
            onProblem(reason(e))
            null
        }
    }

    /** The header of a capture. */
    private fun header(descriptor: Path, time: LocalDateTime, endpoint: RuntimeEndpoint): List<String> =
        listOf(
            "# karpfen run capture",
            "# Descriptor: ${relative(descriptor)}",
            "# Started: ${STARTED.format(time)}",
            "# Runtime: the IDE's Docker container at ${endpoint.httpBase}",
        )

    /**
     * Creates the folder and a new file named [name] in it, with a number added while the name is taken, and writes
     * [header] into it. Returns the file and its open writer. Blocks.
     */
    private fun create(name: String, header: List<String>): Pair<Path, Writer> {
        Files.createDirectories(folder)
        for (attempt in 1..MAX_ATTEMPTS) {
            val file = folder.resolve(if (attempt == 1) "$name$SUFFIX" else "$name-$attempt$SUFFIX")
            val writer = try {
                Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (taken: FileAlreadyExistsException) {
                continue
            }
            try {
                header.forEach { writer.write(it + System.lineSeparator()) }
                writer.flush()
            } catch (e: IOException) {
                writer.close()
                throw e
            }
            return file to writer
        }
        throw IOException("every name from $name$SUFFIX to $name-$MAX_ATTEMPTS$SUFFIX is taken")
    }

    /** [path] relative to the root with forward slashes, or as it is if it lies outside. */
    private fun relative(path: Path): String =
        if (path.startsWith(root)) root.relativize(path).toString().replace('\\', '/') else path.toString()

    companion object {
        /** What starts the line with the environment key. */
        const val ENVIRONMENT: String = "# Environment: "

        /** The suffix of a capture file. */
        const val SUFFIX: String = ".log"

        /** How many names are tried before the capture gives up. */
        private const val MAX_ATTEMPTS = 1_000

        /** The pattern of the file name. */
        private val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        /** The pattern of the start in the header. */
        private val STARTED: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Why [e] happened, in a few words: the kind of failure and its message. */
        fun reason(e: IOException): String = listOfNotNull(e::class.simpleName, e.message).joinToString(": ")
    }
}
