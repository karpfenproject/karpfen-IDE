package io.karpfen.ide.tools.cli

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.tools.cli.validate.ReportFormatter
import io.karpfen.ide.tools.cli.validate.StandardOutputGuard
import io.karpfen.ide.tools.cli.validate.ValidateCommand
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

/**
 * The karpfen command-line tool: parses the arguments ([CliArguments]) and runs the command, returning its exit status
 * ([ExitCodes]). Everything it prints goes through [outputGuard]: the report and the usage of `help` to its `output`
 * (stdout), its own messages (`karpfen: …` and the usage after invalid arguments) to its `errorOutput` (stderr); while
 * the workspace runs, both process streams are silenced ([ValidateCommand]). For now that discards what dsl-tools
 * prints; [StandardOutputGuard] explains why this is interim and what replaces it.
 *
 * @param workspaceFactory opens the validated folder (tests pass test dispatchers).
 */
internal class KarpfenCli(
    private val outputGuard: StandardOutputGuard = StandardOutputGuard(),
    workspaceFactory: WorkspaceFactory = WorkspaceFactory(WorkspaceOptions(watch = false)),
) {
    private val validateCommand = ValidateCommand(outputGuard, workspaceFactory)

    /** Runs the command line [args] and returns the exit status. */
    suspend fun execute(args: List<String>): Int = when (val command = CliArguments.parse(args)) {
        CliCommand.Help -> {
            outputGuard.output.println(USAGE)
            ExitCodes.OK
        }
        is CliCommand.Invalid -> {
            message(command.message)
            outputGuard.errorOutput.println(USAGE)
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
            validateCommand.run(command.directory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Validation of {} failed", command.directory, e)
            message("validation of ${command.directory} failed: ${e.message ?: e::class.java.name}")
            return ExitCodes.FAILURE
        }
        outputGuard.output.print(ReportFormatter(command.format).format(report))
        outputGuard.output.flush()
        return if (report.hasErrors) ExitCodes.ERRORS_FOUND else ExitCodes.OK
    }

    /** Prints the CLI's own message [text] to the error output, prefixed with `karpfen:`. */
    private fun message(text: String) {
        outputGuard.errorOutput.println("karpfen: $text")
    }

    private companion object {
        private val log = LoggerFactory.getLogger(KarpfenCli::class.java)
    }
}
