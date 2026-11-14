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
package io.karpfen.ide.app.headless

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A command of the headless mode, as [HeadlessCommand.parse] reads it from the command line. */
internal sealed interface HeadlessCommand {

    /**
     * `karpfen-ide validate <folder> [--format text|json]`: validates the whole project in [folder] and prints the
     * findings in [format].
     *
     * @property folder the project folder, absolute and normalized. It is an existing directory.
     * @property format how the findings are printed, as text unless `--format json` asks for JSON.
     */
    data class Validate(val folder: Path, val format: OutputFormat = OutputFormat.TEXT) : HeadlessCommand

    /**
     * `karpfen-ide run <folder> [--descriptor <file>] [--bridge <file>]... [--capture] [--true-parallel] [--verbose]`:
     * runs the project in [folder] until it is stopped.
     *
     * @property folder the project folder, absolute and normalized. It is an existing directory.
     * @property descriptor the environment descriptor to run, resolved against [folder] and normalized, or null to
     *   take the only descriptor of the project.
     * @property bridges the bridge files to start with the run, resolved against [folder] and normalized, in the
     *   order they were named, each once. Empty starts no bridge.
     * @property capture whether the run is captured to a file in the project.
     * @property trueParallel whether the parallel branches of the state machines step on threads of their own, which
     *   the runtime feature `TrueParallel` does.
     * @property verbose whether every line of the runtime's log is printed too.
     */
    data class Run(
        val folder: Path,
        val descriptor: Path? = null,
        val bridges: List<Path> = emptyList(),
        val capture: Boolean = false,
        val verbose: Boolean = false,
        val trueParallel: Boolean = false,
    ) : HeadlessCommand

    /** `--help` or `-h` after a headless command. The usage is printed, and the status is 0. */
    data object Help : HeadlessCommand

    /**
     * A headless command line that cannot be carried out. The [message] is printed with the usage, and the status
     * is 2.
     */
    data class Invalid(val message: String) : HeadlessCommand

    companion object {
        /** The word of the command that validates a project. */
        const val VALIDATE: String = "validate"

        /** The word of the command that runs a project. */
        const val RUN: String = "run"

        /**
         * The headless command of [args], or null if [args] do not start with [VALIDATE] or [RUN]. The IDE then opens
         * its window, and the arguments mean what they mean there.
         *
         * Unlike the window, a headless command is strict: an unknown option, a missing, second or empty folder, a
         * folder that [isDirectory] rejects, an option that lacks its value, a second `--descriptor` or `--format`, a
         * format other than `text` or `json`, a bridge named twice, an option that the command does not take, and a
         * path the file system cannot represent are all [Invalid]. Options may stand before and after the folder. A value follows its option as the next argument or
         * after `=`, as in `--bridge=http.kbridge.json`. `--` ends the options, so a folder may start with `-`.
         */
        fun parse(args: List<String>, isDirectory: (Path) -> Boolean = Files::isDirectory): HeadlessCommand? = when (args.firstOrNull()) {
            VALIDATE -> HeadlessArguments(VALIDATE, args.drop(1), isDirectory).parse()
            RUN -> HeadlessArguments(RUN, args.drop(1), isDirectory).parse()
            else -> null
        }
    }
}

/**
 * The arguments after the word of a headless [command], which is [HeadlessCommand.VALIDATE] or [HeadlessCommand.RUN].
 * Each instance parses once.
 */
private class HeadlessArguments(
    private val command: String,
    private val args: List<String>,
    private val isDirectory: (Path) -> Boolean,
) {
    private var folder: String? = null
    private var descriptor: String? = null
    private var format: OutputFormat? = null
    private val bridges = ArrayList<String>()
    private var capture = false
    private var trueParallel = false
    private var verbose = false
    private var optionsEnded = false

    /** The command these arguments stand for. */
    fun parse(): HeadlessCommand {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            val option = arg.substringBefore('=')
            val failure = when {
                optionsEnded || !arg.startsWith("-") || arg == "-" -> addFolder(arg)
                arg == END_OF_OPTIONS -> null.also { optionsEnded = true }
                arg in HELP_FLAGS -> return HeadlessCommand.Help
                option !in OPTIONS.getValue(command) -> refusal(arg, option)
                arg == CAPTURE -> null.also { capture = true }
                arg == TRUE_PARALLEL -> null.also { trueParallel = true }
                arg == VERBOSE -> null.also { verbose = true }
                option in VALUE_OPTIONS -> {
                    val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(++index)
                    if (value.isNullOrEmpty()) "$option needs ${VALUE_OPTIONS.getValue(option)}" else valueOf(option, value)
                }
                else -> "unknown option '$arg'"
            }
            if (failure != null) return HeadlessCommand.Invalid(failure)
            index++
        }
        return build()
    }

    /** The command of the arguments read, once every argument passed. */
    private fun build(): HeadlessCommand {
        val name = folder ?: return HeadlessCommand.Invalid("$command needs a project folder")
        if (name.isEmpty()) return HeadlessCommand.Invalid("$command needs a project folder, not an empty argument")
        val root = pathOf(name)?.toAbsolutePath()?.normalize() ?: return HeadlessCommand.Invalid("invalid path '$name'")
        if (!isDirectory(root)) return HeadlessCommand.Invalid("$root is not a folder")
        if (command == HeadlessCommand.VALIDATE) return HeadlessCommand.Validate(root, format ?: OutputFormat.TEXT)
        val resolvedDescriptor = descriptor?.let { root.resolve(pathOf(it) ?: return HeadlessCommand.Invalid("invalid path '$it'")).normalize() }
        val resolvedBridges = bridges.map { root.resolve(pathOf(it) ?: return HeadlessCommand.Invalid("invalid path '$it'")).normalize() }
        val twice = resolvedBridges.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (twice != null) return HeadlessCommand.Invalid("the bridge ${root.relativize(twice)} is named twice")
        return HeadlessCommand.Run(root, resolvedDescriptor, resolvedBridges, capture, verbose, trueParallel)
    }

    /**
     * The error message for the option [arg], named [option] without its value, which this command does not take. An
     * option of the other command is named as one this command does not take, and any other as unknown.
     */
    private fun refusal(arg: String, option: String): String =
        if (OPTIONS.values.any { option in it }) "$command takes no option '$arg'" else "unknown option '$arg'"

    /** Records the folder. Returns an error message for a second one, and null otherwise. */
    private fun addFolder(arg: String): String? {
        if (folder != null) return "unexpected argument '$arg' ($command takes one project folder)"
        folder = arg
        return null
    }

    /**
     * Records [value] of [option]. Returns an error message for a second descriptor or format and for a format that
     * is neither `text` nor `json`, and null otherwise.
     */
    private fun valueOf(option: String, value: String): String? {
        when (option) {
            BRIDGE -> bridges += value
            FORMAT -> {
                if (format != null) return "$FORMAT may be given once"
                format = OutputFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: return "$FORMAT takes $FORMAT_VALUES, not '$value'"
            }
            else -> {
                if (descriptor != null) return "$DESCRIPTOR may be given once"
                descriptor = value
            }
        }
        return null
    }

    /** [name] as a path, or null if the file system cannot represent it. */
    private fun pathOf(name: String): Path? = try {
        Path.of(name)
    } catch (e: InvalidPathException) {
        null
    }

    private companion object {
        /** The option that names the environment descriptor to run. */
        const val DESCRIPTOR = "--descriptor"

        /** The option that names a bridge file to start with the run. */
        const val BRIDGE = "--bridge"

        /** The option that captures the run to a file. */
        const val CAPTURE = "--capture"

        /** The option that steps the parallel branches of the state machines on threads of their own. */
        const val TRUE_PARALLEL = "--true-parallel"

        /** The option that prints every line of the runtime's log. */
        const val VERBOSE = "--verbose"

        /** The option that chooses the output format of a validation. */
        const val FORMAT = "--format"

        /** The values of [FORMAT] in words. */
        const val FORMAT_VALUES = "text or json"

        /** Ends the options: every later argument is the folder. */
        const val END_OF_OPTIONS = "--"

        /** The options that take a value, each with what its value is in words. */
        val VALUE_OPTIONS: Map<String, String> = mapOf(DESCRIPTOR to "a file", BRIDGE to "a file", FORMAT to FORMAT_VALUES)

        /** The options each command takes, by the word of the command. */
        val OPTIONS: Map<String, Set<String>> = mapOf(
            HeadlessCommand.VALIDATE to setOf(FORMAT),
            HeadlessCommand.RUN to setOf(DESCRIPTOR, BRIDGE, CAPTURE, TRUE_PARALLEL, VERBOSE),
        )

        /** The options that ask for the usage. */
        val HELP_FLAGS = setOf("--help", "-h")
    }
}
