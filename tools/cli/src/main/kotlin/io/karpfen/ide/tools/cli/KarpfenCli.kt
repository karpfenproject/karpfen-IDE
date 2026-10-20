package io.karpfen.ide.tools.cli

import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.tools.cli.validate.ReportFormatter
import io.karpfen.ide.tools.cli.validate.StandardOutputGuard
import io.karpfen.ide.tools.cli.validate.ValidateCommand
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

/** Exit statuses of the karpfen command-line tool. */
public object ExitCodes {
    /** The usage was printed, or the validation found no ERROR. */
    public const val OK: Int = 0

    /** The validation found at least one ERROR. */
    public const val ERRORS_FOUND: Int = 1

    /** Invalid command line, or the directory to validate does not exist. */
    public const val USAGE: Int = 2

    /** The validation failed unexpectedly (a defect; the cause is logged). */
    public const val FAILURE: Int = 3
}

/**
 * The karpfen command-line tool: parses the arguments ([CliArguments]) and runs the command, returning its exit status
 * ([ExitCodes]). Everything it prints goes through [outputGuard]: the report and the usage of `help` to its `output`
 * (stdout), its own messages (`karpfen: …` and the usage after invalid arguments) to its `errorOutput` (stderr); while
 * the workspace runs, both process streams are silenced ([ValidateCommand]).
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

    private fun message(text: String) {
        outputGuard.errorOutput.println("karpfen: $text")
    }

    private companion object {
        private val log = LoggerFactory.getLogger(KarpfenCli::class.java)
    }
}
