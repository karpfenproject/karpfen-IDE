package io.karpfen.ide.tools.cli

import io.karpfen.ide.tools.cli.validate.OutputFormat
import java.nio.file.Path

/** A parsed command line. */
public sealed interface CliCommand {
    /** `karpfen validate <dir> [--format text|json]`. */
    public data class Validate(public val directory: Path, public val format: OutputFormat) : CliCommand

    /** No arguments, `help`, `--help` or `-h`: print the usage. */
    public data object Help : CliCommand

    /** Unparseable arguments: print [message] and the usage, exit 2. */
    public data class Invalid(public val message: String) : CliCommand
}

/** Command-line parsing (pure). */
public object CliArguments {
    /** Parses [args]. */
    public fun parse(args: List<String>): CliCommand = TODO("WP-APP: parse command-line arguments")
}
