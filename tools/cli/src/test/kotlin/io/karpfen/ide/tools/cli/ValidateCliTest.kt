package io.karpfen.ide.tools.cli

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `karpfen validate` end to end on real folders (spec §9, §11 item 5): the examples, broken copies, loose files and the
 * shared descriptor examples, through the real workspace on virtual time, with both process streams captured.
 */
class ValidateCliTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the examples validate with exit 0 and print nothing`(reporter: TestReporter) = runTest(timeout = 120.seconds) {
        val root = Examples.copyTo(dir)

        val mark = TimeSource.Monotonic.markNow()
        val run = runCli(testWorkspaceFactory(this), "validate", root.toString())
        reporter.publishEntry("karpfen validate <examples>", "${mark.elapsedNow()} (workspace scan, resolution and analysis)")

        assertEquals(CliRun(ExitCodes.OK, stdout = "", stderr = ""), run)
    }

    @Test
    fun `the examples without the guard make dsl-tools print, so the clean output above is the guard's doing`() =
        runTest(timeout = 120.seconds) {
            val root = Examples.copyTo(dir)

            val printed = ProcessStreams().use { streams ->
                val workspace = testWorkspaceFactory(this).open(root, backgroundScope)
                workspace.awaitAnalysis()
                workspace.close()
                streams.stdout() + streams.stderr()
            }

            assertTrue(printed.isNotEmpty(), "dsl-tools' converters print while converting the examples")
        }

    @Test
    fun `a broken copy exits 1 with the one-based position of the syntax error and prints nothing else`() =
        runTest(timeout = 120.seconds) {
            val root = Examples.copyTo(dir)
            val broken = "karpfen-runtime/single_robot_example/cleaning_robot.kmeta"
            val text = Files.readString(root.resolve(broken)).replaceFirst(VALID_PROPERTY, PROPERTY_WITHOUT_COMMA)
            Files.writeString(root.resolve(broken), text)
            val line = text.lines().indexOfFirst { PROPERTY_WITHOUT_COMMA in it }
            val column = text.lines()[line].indexOf("\"number\"")

            val run = runCli(testWorkspaceFactory(this), "validate", root.toString())

            assertEquals(ExitCodes.ERRORS_FOUND, run.status)
            assertEquals(
                listOf("$broken:${line + 1}:${column + 1}: error: missing ',' at '\"number\"' [${DiagnosticCodes.SYNTAX_ERROR}]"),
                run.lines,
            )
            assertEquals("", run.stderr, "nothing on stderr but the CLI's own messages (none here)")
        }

    @Test
    fun `a second, broken metamodel is a loose file whose syntax error is reported next to the context INFO`() =
        runTest(timeout = 120.seconds) {
            val root = Examples.copyTo(dir)
            val directory = "karpfen-runtime/single_robot_example"
            Files.writeString(root.resolve("$directory/zz.kmeta"), "type \"B\" \"b\" {\n    prop(\"y\", \"number\")\n")

            val run = runCli(testWorkspaceFactory(this), "validate", root.toString())

            assertEquals(ExitCodes.ERRORS_FOUND, run.status)
            val info = WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS
            assertEquals(
                listOf(
                    "$directory/cleaning_robot.kmeta:1:1: info: [$info]",
                    "$directory/zz.kmeta:1:1: info: [$info]",
                    "$directory/zz.kmeta:3:1: error: missing '}' at '<EOF>' [${DiagnosticCodes.SYNTAX_ERROR}]",
                ),
                run.lines.map { it.withoutMessageOf(info) },
            )
            assertEquals("", run.stderr)
        }

    @Test
    fun `descriptor findings are reported at their positions in the descriptor`() = runTest(timeout = 60.seconds) {
        val descriptor = DescriptorExamples.copyExplicitContext(dir.resolve("ctx"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
        val text = Files.readString(descriptor)

        val run = runCli(testWorkspaceFactory(this), "validate", dir.toString())

        assertEquals(ExitCodes.ERRORS_FOUND, run.status)
        val path = "ctx/${DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE}"
        assertEquals(
            listOf(
                "$path:${positionOf(text, "missing.kmodel")}: error: [${WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE}]",
                "$path:${positionOf(text, "tickDelay")}: warning: [${WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY}]",
            ),
            run.lines.map { it.withoutMessage() },
        )
        assertTrue("missing.kmodel" in run.lines.first())
        assertEquals("", run.stderr)
    }

    @Test
    fun `an invalid descriptor exits 1 and a valid one exits 0 without output`() = runTest(timeout = 60.seconds) {
        DescriptorExamples.copyExplicitContext(dir.resolve("invalid"), DescriptorExamples.INVALID_JSON)
        DescriptorExamples.copyExplicitContext(dir.resolve("valid"), DescriptorExamples.VALID)

        val invalid = runCli(testWorkspaceFactory(this), "validate", dir.resolve("invalid").toString())
        val valid = runCli(testWorkspaceFactory(this), "validate", dir.resolve("valid").toString())

        assertEquals(ExitCodes.ERRORS_FOUND, invalid.status)
        val line = invalid.lines.single()
        assertTrue(line.startsWith("${DescriptorExamples.INVALID_JSON}:"), line)
        assertTrue(line.endsWith("[${WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID}]"), line)
        assertEquals(CliRun(ExitCodes.OK, stdout = "", stderr = ""), valid)
    }

    @Test
    fun `the JSON format parses and carries the same problems as the text format`() = runTest(timeout = 120.seconds) {
        val root = Examples.copyTo(dir)
        Files.writeString(root.resolve("karpfen-runtime/single_robot_example/zz.kmeta"), "type \"B\" \"b\" {\n")

        val text = runCli(testWorkspaceFactory(this), "validate", root.toString())
        val json = runCli(testWorkspaceFactory(this), "validate", "--format=json", root.toString())

        assertEquals(ExitCodes.ERRORS_FOUND, json.status)
        assertEquals("", json.stderr)
        val document = Json.parseToJsonElement(json.stdout).jsonObject
        assertEquals(root.toAbsolutePath().normalize().toString(), document.getValue("root").jsonPrimitive.content)
        assertEquals(1, document.getValue("errorCount").jsonPrimitive.int)
        assertEquals(0, document.getValue("warningCount").jsonPrimitive.int)
        val asText = document.getValue("problems").jsonArray.map { element ->
            val problem = element.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
            "${problem["path"]}:${problem["line"]}:${problem["column"]}: ${problem["severity"]}: ${problem["message"]} [${problem["code"]}]"
        }
        assertEquals(text.lines, asText)
    }

    @Test
    fun `non-ASCII paths and messages are reported in UTF-8 also when the console encoding is ASCII`() =
        runTest(timeout = 60.seconds) {
            val file = Files.createDirectories(dir.resolve("Übung")).resolve("größe.kmeta")
            Files.writeString(file, "type \"Größe\" \"g\" {\n")

            val json = runCli(testWorkspaceFactory(this), "validate", "--format", "json", dir.toString(), consoleEncoding = Charsets.US_ASCII)
            val text = runCli(testWorkspaceFactory(this), "validate", dir.toString(), consoleEncoding = Charsets.US_ASCII)
            val missing = runCli(testWorkspaceFactory(this), "validate", dir.resolve("Übung/fehlt").toString(), consoleEncoding = Charsets.US_ASCII)

            assertEquals(ExitCodes.ERRORS_FOUND, json.status)
            val problems = Json.parseToJsonElement(json.stdout).jsonObject.getValue("problems").jsonArray
            assertEquals(listOf("Übung/größe.kmeta"), problems.map { it.jsonObject.getValue("path").jsonPrimitive.content })
            assertTrue(text.lines.single().startsWith("Übung/größe.kmeta:"), text.stdout)
            assertEquals("karpfen: not a directory: ${dir.resolve("Übung/fehlt")}\n", missing.stderr)
        }

    @Test
    fun `a directory that does not exist is a usage error on stderr`() = runTest {
        val missing = dir.resolve("nope")

        val run = runCli(testWorkspaceFactory(this), "validate", missing.toString())

        assertEquals(CliRun(ExitCodes.USAGE, stdout = "", stderr = "karpfen: not a directory: $missing\n"), run)
    }

    @Test
    fun `an empty directory argument is a usage error, not the working directory`() = runTest {
        val run = runCli(testWorkspaceFactory(this), "validate", "")

        assertEquals(CliRun(ExitCodes.USAGE, stdout = "", stderr = "karpfen: validate needs a directory, not an empty argument\n$USAGE\n"), run)
    }

    @Test
    fun `help prints the usage on stdout and invalid arguments print the message and the usage on stderr`() = runTest {
        val help = runCli(testWorkspaceFactory(this))
        val invalid = runCli(testWorkspaceFactory(this), "deploy")

        assertEquals(CliRun(ExitCodes.OK, stdout = "$USAGE\n", stderr = ""), help)
        assertEquals(CliRun(ExitCodes.USAGE, stdout = "", stderr = "karpfen: unknown command 'deploy'\n$USAGE\n"), invalid)
    }

    @Test
    fun `karpfen files and directories it cannot read are errors, never a silent pass`() = runTest(timeout = 60.seconds) {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "needs POSIX permissions")
        val unreadableFile = Files.writeString(dir.resolve("broken.kmeta"), "type \"A\" \"a\" {\n")
        val unreadableDirectory = Files.createDirectories(dir.resolve("sub"))
        Files.writeString(unreadableDirectory.resolve("inner.kmeta"), "type \"B\" \"b\" {\n")
        val otherFile = Files.writeString(dir.resolve("notes.txt"), "not a karpfen file")
        val locked = listOf(unreadableFile, unreadableDirectory, otherFile)
        locked.forEach { Files.setPosixFilePermissions(it, emptySet()) }
        try {
            assumeFalse(Files.isReadable(unreadableFile), "the user can read any file (root)")

            val run = runCli(testWorkspaceFactory(this), "validate", dir.toString())

            assertEquals(ExitCodes.ERRORS_FOUND, run.status, "not validated is not valid")
            assertEquals(
                listOf("broken.kmeta:1:1: error: [unreadable]", "sub:1:1: error: [unreadable]"),
                run.lines.map { it.withoutMessage() },
                "unreadable karpfen files and directories are named; other files are not validated anyway",
            )
            assertEquals("", run.stderr)
        } finally {
            Files.setPosixFilePermissions(unreadableDirectory, PosixFilePermissions.fromString("rwx------"))
            listOf(unreadableFile, otherFile).forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------")) }
        }
    }

    @Test
    fun `a failing workspace exits 3 with a message and no report`() = runTest {
        val run = runCli({ _, _ -> throw IllegalStateException("disk on fire") }, "validate", dir.toString())

        assertEquals(ExitCodes.FAILURE, run.status)
        assertEquals("", run.stdout)
        assertTrue("karpfen: validation of $dir failed: disk on fire\n" in run.stderr, run.stderr)
    }

    /** One-based `line:col` of the first occurrence of [needle] in [text]. */
    private fun positionOf(text: String, needle: String): String {
        val line = text.lines().indexOfFirst { needle in it }
        return "${line + 1}:${text.lines()[line].indexOf(needle) + 1}"
    }

    /** The report line without its message: `path:line:col: severity: [code]`. */
    private fun String.withoutMessage(): String {
        val match = requireNotNull(REPORT_LINE.matchEntire(this)) { "not a report line: $this" }
        val (location, severity, _, code) = match.destructured
        return "$location: $severity: [$code]"
    }

    /** [withoutMessage] for lines with [code] only. */
    private fun String.withoutMessageOf(code: String): String = if (endsWith("[$code]")) withoutMessage() else this

    private companion object {
        const val VALID_PROPERTY = "prop(\"y\", \"number\")"
        const val PROPERTY_WITHOUT_COMMA = "prop(\"y\" \"number\")"

        /** `path:line:col: severity: message [code]`. */
        val REPORT_LINE = Regex("""^(.+:\d+:\d+): (error|warning|info|hint): (.*) \[([^\]]+)]$""")
    }
}
