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

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.WorkspaceFactory
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.tools.cli.logging.LanguageToolsLog
import io.karpfen.ide.tools.cli.logging.LogbackLanguageToolsLog
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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `karpfen validate` end to end on real folders: the examples (exit 0, nothing printed), broken copies, loose files and
 * the shared descriptor examples, through the real workspace on virtual time, with both process streams captured; what
 * dsl-tools logs stays off unless `--verbose` shows it on standard error.
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

    /**
     * Proves that nothing prints while a workspace analyzes the examples, so the report needs no guard around the
     * analysis: the dsl-tools readers report what they find as diagnostics and log nothing, even while what dsl-tools
     * logs is shown at DEBUG, as `--verbose` shows it.
     */
    @Test
    fun `analyzing the examples writes nothing to the console, even with the loggers of dsl-tools at DEBUG`() =
        runTest(timeout = 120.seconds) {
            val root = Examples.copyTo(dir)

            val (stdout, stderr) = LogbackLanguageToolsLog.ofApplication().show().use {
                ProcessStreams().use { streams ->
                    val workspace = testWorkspaceFactory(this).open(root, backgroundScope)
                    workspace.refresh()
                    workspace.awaitAnalysis()
                    workspace.close()
                    streams.stdout() to streams.stderr()
                }
            }

            assertEquals("", stdout)
            assertEquals("", stderr, "the dsl-tools readers report through diagnostics, not through logging")
        }

    /**
     * The readers of dsl-tools log nothing while a workspace analyzes, so a workspace that logs through a logger of
     * dsl-tools while it opens stands in for what dsl-tools logs during a validation.
     */
    @Test
    fun `verbose shows what dsl-tools logs on standard error and leaves standard output to the report`() =
        runTest(timeout = 120.seconds) {
            val root = Examples.copyTo(dir)
            Files.writeString(root.resolve("karpfen-runtime/single_robot_example/zz.kmeta"), "type \"B\" \"b\" {\n")
            val workspaces = testWorkspaceFactory(this)
            val loggingWorkspaces = WorkspaceFactory { folder, scope ->
                LoggerFactory.getLogger(KMETA_CONVERTER_LOGGER).debug("Type {}: {}", "B", "b")
                workspaces.open(folder, scope)
            }

            val run = runCli(loggingWorkspaces, "validate", "--format", "json", "--verbose", root.toString())

            assertEquals(ExitCodes.ERRORS_FOUND, run.status)
            assertEquals(1, Json.parseToJsonElement(run.stdout).jsonObject.getValue("errorCount").jsonPrimitive.int)
            val logged = run.stderr.lines().filter { it.isNotEmpty() }
            assertTrue(logged.any { " DEBUG " in it && "KmetaDSLConverter" in it }, run.stderr)
            assertTrue(logged.all { DSL_TOOLS_LINE.containsMatchIn(it) }, "only what dsl-tools logs: ${run.stderr}")
            assertFalse(LoggerFactory.getLogger(DslToolsLoggers.PARENT).isErrorEnabled, "the verbose run turned dsl-tools off again")
        }

    @Test
    fun `without verbose nothing of what dsl-tools logs is shown, and verbose shows it for the validation only`() = runTest {
        val shown = mutableListOf<String>()
        val log = LanguageToolsLog {
            shown += "show"
            AutoCloseable { shown += "close" }
        }
        val root = Files.createDirectories(dir.resolve("empty"))

        runCli(testWorkspaceFactory(this), "validate", root.toString(), languageToolsLog = log)
        assertEquals(emptyList(), shown)
        runCli({ _, _ -> shown += "validate"; error("disk on fire") }, "validate", "--verbose", root.toString(), languageToolsLog = log)
        assertEquals(listOf("show", "validate", "close"), shown, "also when the validation fails")
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
        assertEquals("${DescriptorExamples.INVALID_JSON}:3:11: error: Expected a value after ':'. [${DiagnosticCodes.SYNTAX_ERROR}]", line)
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
            val warnings = run.stderr.lines().filter { it.isNotEmpty() }
            assertTrue(warnings.isNotEmpty(), "the workspace's own warning reaches standard error")
            assertTrue(warnings.all { " WARN " in it && "Cannot list directory $unreadableDirectory" in it }, run.stderr)
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
        /** The logger of the KMeta converter of dsl-tools, which logs at DEBUG what it converts. */
        const val KMETA_CONVERTER_LOGGER = "${DslToolsLoggers.PARENT}.dsl.textual.KmetaDSLConverter"

        /** A line that the test configuration's console writes for an event of a dsl-tools logger. */
        val DSL_TOOLS_LINE = Regex("""^\S+ (TRACE|DEBUG|INFO|WARN|ERROR) +i\.k\.d\.""")

        /** A property declaration of the example metamodel. */
        const val VALID_PROPERTY = "prop(\"y\", \"number\")"

        /** [VALID_PROPERTY] without its comma, a syntax error. */
        const val PROPERTY_WITHOUT_COMMA = "prop(\"y\" \"number\")"

        /** `path:line:col: severity: message [code]`. */
        val REPORT_LINE = Regex("""^(.+:\d+:\d+): (error|warning|info|hint): (.*) \[([^\]]+)]$""")
    }
}
