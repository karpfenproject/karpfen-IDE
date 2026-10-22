package io.karpfen.ide.tools.cli

import io.karpfen.ide.tools.cli.validate.OutputFormat
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [CliArguments]: the help words, `validate` with its options (format and verbose) before or after the directory, `--`
 * ending the options, and one-line messages for malformed command lines.
 */
class CliArgumentsTest {

    private fun parse(vararg args: String): CliCommand = CliArguments.parse(args.toList())

    @Test
    fun `no arguments and the help words print the usage`() {
        listOf(emptyList(), listOf("help"), listOf("--help"), listOf("-h"), listOf("validate", "--help"), listOf("validate", "dir", "-h"))
            .forEach { args -> assertEquals(CliCommand.Help, CliArguments.parse(args), "$args") }
    }

    @Test
    fun `validate takes one directory and the text format by default`() {
        assertEquals(CliCommand.Validate(Path.of("examples"), OutputFormat.TEXT), parse("validate", "examples"))
    }

    @Test
    fun `the format option is accepted before or after the directory, separated or with an equals sign, in any case`() {
        val json = CliCommand.Validate(Path.of("dir"), OutputFormat.JSON)
        assertEquals(json, parse("validate", "--format", "json", "dir"))
        assertEquals(json, parse("validate", "dir", "--format", "JSON"))
        assertEquals(json, parse("validate", "--format=Json", "dir"))
        assertEquals(CliCommand.Validate(Path.of("dir"), OutputFormat.TEXT), parse("validate", "dir", "--format=text"))
        assertEquals(json, parse("validate", "--format", "text", "--format", "json", "dir"), "the last format wins")
    }

    @Test
    fun `the verbose option is accepted before or after the directory and is off by default`() {
        val verbose = CliCommand.Validate(Path.of("dir"), OutputFormat.TEXT, verbose = true)
        assertEquals(verbose, parse("validate", "--verbose", "dir"))
        assertEquals(verbose, parse("validate", "dir", "--verbose"))
        assertEquals(verbose.copy(format = OutputFormat.JSON), parse("validate", "--format=json", "dir", "--verbose"))
        assertEquals(false, assertIs<CliCommand.Validate>(parse("validate", "dir")).verbose)
        assertEquals(CliCommand.Validate(Path.of("--verbose"), OutputFormat.TEXT), parse("validate", "--", "--verbose"))
    }

    @Test
    fun `a double dash ends the options, so a directory may start with a dash`() {
        assertEquals(CliCommand.Validate(Path.of("-odd"), OutputFormat.JSON), parse("validate", "--format", "json", "--", "-odd"))
        assertEquals(CliCommand.Validate(Path.of("-"), OutputFormat.TEXT), parse("validate", "-"))
    }

    @Test
    fun `malformed command lines are invalid with a one-line message`() {
        val cases = mapOf(
            listOf("deploy") to "unknown command 'deploy'",
            listOf("validate") to "validate needs a directory",
            listOf("validate", "--format", "json") to "validate needs a directory",
            listOf("validate", "a", "b") to "unexpected argument 'b' (validate takes one directory)",
            listOf("validate", "dir", "--format") to "--format needs a value (text or json)",
            listOf("validate", "dir", "--format", "xml") to "unknown format 'xml' (text or json)",
            listOf("validate", "--format=", "dir") to "unknown format '' (text or json)",
            listOf("validate", "--quiet", "dir") to "unknown option '--quiet'",
            listOf("validate", "-v", "dir") to "unknown option '-v'",
            listOf("validate", "") to "validate needs a directory, not an empty argument",
            listOf("validate", "--format", "json", "--", "") to "validate needs a directory, not an empty argument",
        )
        cases.forEach { (args, message) -> assertEquals(CliCommand.Invalid(message), CliArguments.parse(args), "$args") }
    }

    @Test
    fun `a path the file system cannot represent is invalid instead of throwing`() {
        val result = parse("validate", "bad\u0000path")

        val invalid = assertIs<CliCommand.Invalid>(result)
        assertEquals(true, invalid.message.startsWith("invalid path 'bad"), invalid.message)
        assertEquals(false, '\n' in invalid.message)
    }
}
