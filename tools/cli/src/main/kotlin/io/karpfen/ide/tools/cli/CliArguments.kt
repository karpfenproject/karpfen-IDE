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

import io.karpfen.ide.tools.cli.validate.OutputFormat
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A parsed command line. */
public sealed interface CliCommand {
    /**
     * `karpfen validate <dir> [--format text|json] [--verbose]`.
     *
     * @property directory the folder to validate.
     * @property format the format of the report.
     * @property verbose shows what dsl-tools logs while validating on standard error, which is off otherwise.
     */
    public data class Validate(
        public val directory: Path,
        public val format: OutputFormat,
        public val verbose: Boolean = false,
    ) : CliCommand

    /** No arguments, `help`, `--help` or `-h`. The tool prints the usage. */
    public data object Help : CliCommand

    /** Arguments that cannot be parsed. The tool prints [message] and the usage and exits with status 2. */
    public data class Invalid(public val message: String) : CliCommand
}

/**
 * Parses the command line. The parsing is pure.
 *
 * No arguments, `help`, `--help` and `-h` become [CliCommand.Help]. `validate [options] <dir>` becomes
 * [CliCommand.Validate]. Its options may stand before or after the directory. The format option is
 * `--format text|json` or `--format=text|json`. Its value is case insensitive, and the default is text. `--verbose`
 * shows what dsl-tools logs. `--help` or `-h` makes the whole command a [CliCommand.Help]. `--` ends the options, so a
 * directory may start with `-`. A lone `-` counts as a directory.
 *
 * Everything else becomes [CliCommand.Invalid] with a one-line message. That covers an unknown command, an unknown
 * option, a missing, empty or second directory, a bad format value and a path the file system cannot represent. An
 * empty directory argument, as in `karpfen validate "$UNSET"`, never means the working directory, which `Path.of("")`
 * would denote.
 */
public object CliArguments {

    /** Parses [args]. */
    public fun parse(args: List<String>): CliCommand {
        val command = args.firstOrNull() ?: return CliCommand.Help
        return when (command) {
            in HELP_WORDS -> CliCommand.Help
            VALIDATE -> ValidateArguments(args.drop(1)).parse()
            else -> CliCommand.Invalid("unknown command '$command'")
        }
    }

    private const val VALIDATE = "validate"
    private val HELP_WORDS = setOf("help", "--help", "-h")
}

/** The arguments after `validate`. Each instance parses once. */
private class ValidateArguments(private val args: List<String>) {
    private var directory: String? = null
    private var format = OutputFormat.TEXT
    private var verbose = false
    private var optionsEnded = false

    /** The command these arguments stand for: [CliCommand.Validate], [CliCommand.Help] or [CliCommand.Invalid]. */
    fun parse(): CliCommand {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            val failure = when {
                optionsEnded || !arg.startsWith("-") || arg == "-" -> addDirectory(arg)
                arg == END_OF_OPTIONS -> null.also { optionsEnded = true }
                arg in HELP_FLAGS -> return CliCommand.Help
                arg == VERBOSE_OPTION -> null.also { verbose = true }
                arg == FORMAT_OPTION -> {
                    index++
                    setFormat(args.getOrNull(index) ?: return CliCommand.Invalid("$FORMAT_OPTION needs a value (text or json)"))
                }
                arg.startsWith("$FORMAT_OPTION=") -> setFormat(arg.substringAfter('='))
                else -> "unknown option '$arg'"
            }
            if (failure != null) return CliCommand.Invalid(failure)
            index++
        }
        val name = directory ?: return CliCommand.Invalid("validate needs a directory")
        if (name.isEmpty()) return CliCommand.Invalid("validate needs a directory, not an empty argument")
        val path = try {
            Path.of(name)
        } catch (e: InvalidPathException) {
            return CliCommand.Invalid("invalid path '$name': ${e.reason}")
        }
        return CliCommand.Validate(path, format, verbose)
    }

    /** Records the directory. Returns an error message for a second directory, and null otherwise. */
    private fun addDirectory(arg: String): String? {
        if (directory != null) return "unexpected argument '$arg' (validate takes one directory)"
        directory = arg
        return null
    }

    /** Records the format. Returns an error message for an unknown value, and null otherwise. */
    private fun setFormat(value: String): String? {
        format = OutputFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: return "unknown format '$value' (text or json)"
        return null
    }

    private companion object {
        /** The option naming the output format, followed by its value or joined to it by `=`. */
        const val FORMAT_OPTION = "--format"

        /** The option that shows what dsl-tools logs while validating. */
        const val VERBOSE_OPTION = "--verbose"

        /** Ends the options: every later argument is the directory. */
        const val END_OF_OPTIONS = "--"

        /** The options that ask for the usage. */
        val HELP_FLAGS = setOf("--help", "-h")
    }
}
