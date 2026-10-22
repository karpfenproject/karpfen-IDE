package io.karpfen.ide.tools.cli

import java.io.PrintStream

/**
 * The console of the command-line tool: [output], standard output, carries the report and the usage of `help` and
 * nothing else, so a JSON report stays machine-readable; [errorOutput], standard error, carries the tool's own messages
 * and, through the logging backend, the log: the tool's warnings and, with `--verbose`, what dsl-tools logs. Nothing
 * else prints to standard output while a folder is validated, which a test of `karpfen validate` on the examples
 * proves, so the report needs no guard around the analysis.
 *
 * The default streams encode UTF-8 onto the process streams captured at construction, whatever the console's encoding
 * (`stdout.encoding`, US-ASCII under `LANG=C` in containers and minimal CI runners), so paths and messages with
 * non-ASCII characters keep naming their files instead of turning into `?`.
 */
internal class CliConsole(
    val output: PrintStream = utf8(System.out),
    val errorOutput: PrintStream = utf8(System.err),
) {
    private companion object {
        /** A stream encoding UTF-8 onto [stream] (bytes pass a `PrintStream` unchanged, whatever its own encoding). */
        fun utf8(stream: PrintStream): PrintStream = PrintStream(stream, true, Charsets.UTF_8)
    }
}
