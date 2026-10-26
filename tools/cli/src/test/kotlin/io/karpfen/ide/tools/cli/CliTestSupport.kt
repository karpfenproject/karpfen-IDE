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
package io.karpfen.ide.tools.cli

import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.tools.cli.logging.LanguageToolsLog
import io.karpfen.ide.tools.cli.logging.LogbackLanguageToolsLog
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.time.TestTimeSource

/**
 * Real workspaces without watching on the virtual time of [scope]:
 * `WorkspaceFactory(WorkspaceOptions(watch = false, …test dispatchers…))`, the factory `ValidateCommand` takes in
 * tests.
 */
internal fun testWorkspaceFactory(scope: TestScope): WorkspaceFactory {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    return WorkspaceFactory(
        WorkspaceOptions(
            watch = false,
            ioDispatcher = dispatcher,
            analysisDispatcher = dispatcher,
            sessionConfig = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
            timeSource = TestTimeSource(),
        ),
    )
}

/**
 * Replaces the process streams `System.out` and `System.err` by captures until [close], so a test sees everything the
 * process would have printed (the CLI's console, created inside, takes the captures as its process streams). The
 * captures encode text written to them as [encoding] (the console's, e.g. US-ASCII under `LANG=C`); [stdout] and
 * [stderr] decode the captured bytes as UTF-8.
 */
internal class ProcessStreams(encoding: Charset = Charsets.UTF_8) : AutoCloseable {
    private val previousOut: PrintStream = System.out
    private val previousErr: PrintStream = System.err
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    init {
        System.setOut(PrintStream(out, true, encoding))
        System.setErr(PrintStream(err, true, encoding))
    }

    /** Everything written to `System.out` so far. */
    fun stdout(): String = out.toString(Charsets.UTF_8)

    /** Everything written to `System.err` so far. */
    fun stderr(): String = err.toString(Charsets.UTF_8)

    override fun close() {
        System.setOut(previousOut)
        System.setErr(previousErr)
    }
}

/** The exit status and the process output of one CLI run. */
internal data class CliRun(val status: Int, val stdout: String, val stderr: String) {
    /** The standard output's lines (without the final line break). */
    val lines: List<String> get() = stdout.lines().filter { it.isNotEmpty() }
}

/**
 * Runs the command line [args] like `main` does, capturing the process streams (encoding text as [consoleEncoding]) for
 * the whole run; `--verbose` shows what dsl-tools logs through [languageToolsLog], by default the real one of logback.
 */
internal suspend fun runCli(
    workspaceFactory: WorkspaceFactory,
    vararg args: String,
    consoleEncoding: Charset = Charsets.UTF_8,
    languageToolsLog: LanguageToolsLog = LogbackLanguageToolsLog.ofApplication(),
): CliRun =
    ProcessStreams(consoleEncoding).use { streams ->
        val status = KarpfenCli(CliConsole(), workspaceFactory, languageToolsLog).execute(args.toList())
        CliRun(status, streams.stdout(), streams.stderr())
    }
