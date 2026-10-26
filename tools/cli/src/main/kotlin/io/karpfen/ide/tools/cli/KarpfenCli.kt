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

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.tools.cli.logging.LanguageToolsLog
import io.karpfen.ide.tools.cli.logging.LogbackLanguageToolsLog
import io.karpfen.ide.tools.cli.validate.ReportFormatter
import io.karpfen.ide.tools.cli.validate.ValidateCommand
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

/**
 * The karpfen command-line tool: parses the arguments ([CliArguments]) and runs the command, returning its exit status
 * ([ExitCodes]). Everything it prints goes through [console]: the report and the usage of `help` to its `output`
 * (stdout), its own messages (`karpfen: …` and the usage after invalid arguments) to its `errorOutput` (stderr). What
 * dsl-tools logs while a folder is validated stays off unless the command asks for it with `--verbose`, which shows it
 * on standard error through [languageToolsLog] for the length of the validation.
 *
 * @param workspaceFactory opens the validated folder (tests pass test dispatchers).
 */
internal class KarpfenCli(
    private val console: CliConsole = CliConsole(),
    workspaceFactory: WorkspaceFactory = WorkspaceFactory(WorkspaceOptions(watch = false)),
    private val languageToolsLog: LanguageToolsLog = LogbackLanguageToolsLog.ofApplication(),
) {
    private val validateCommand = ValidateCommand(workspaceFactory)

    /** Runs the command line [args] and returns the exit status. */
    suspend fun execute(args: List<String>): Int = when (val command = CliArguments.parse(args)) {
        CliCommand.Help -> {
            console.output.println(USAGE)
            ExitCodes.OK
        }
        is CliCommand.Invalid -> {
            message(command.message)
            console.errorOutput.println(USAGE)
            ExitCodes.USAGE
        }
        is CliCommand.Validate -> validate(command)
    }

    /**
     * Validates the command's directory and prints the report; the exit status tells whether an ERROR was found. A
     * missing directory is a usage error, an unexpected failure is logged and reported as [ExitCodes.FAILURE].
     */
    private suspend fun validate(command: CliCommand.Validate): Int {
        if (!Files.isDirectory(command.directory)) {
            message("not a directory: ${command.directory}")
            return ExitCodes.USAGE
        }
        val report = try {
            showingLanguageToolsLogIf(command.verbose) { validateCommand.run(command.directory) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Validation of {} failed", command.directory, e)
            message("validation of ${command.directory} failed: ${e.message ?: e::class.java.name}")
            return ExitCodes.FAILURE
        }
        console.output.print(ReportFormatter(command.format).format(report))
        console.output.flush()
        return if (report.hasErrors) ExitCodes.ERRORS_FOUND else ExitCodes.OK
    }

    /** Runs [block], with what dsl-tools logs shown on standard error while it runs if [verbose]. */
    private suspend fun <T> showingLanguageToolsLogIf(verbose: Boolean, block: suspend () -> T): T {
        val shown = if (verbose) languageToolsLog.show() else null
        try {
            return block()
        } finally {
            shown?.close()
        }
    }

    /** Prints the CLI's own message [text] to the error output, prefixed with `karpfen:`. */
    private fun message(text: String) {
        console.errorOutput.println("karpfen: $text")
    }

    private companion object {
        private val log = LoggerFactory.getLogger(KarpfenCli::class.java)
    }
}
