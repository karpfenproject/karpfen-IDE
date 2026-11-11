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
import io.karpfen.ide.core.document.fixtures.DeepNesting
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.SmallStackDslReader
import io.karpfen.ide.core.document.parse.NestingLimit
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
 * `karpfen validate` end to end on real folders, through the real workspace on virtual time, with both process streams
 * captured. The folders are the examples, which exit with 0 and print nothing, broken copies, loose files and the
 * shared descriptor examples. What dsl-tools logs stays off unless `--verbose` shows it on standard error.
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
     * analysis. The dsl-tools readers report what they find as diagnostics and log only a read that fails. No example
     * makes a read fail, and nothing prints even while what dsl-tools logs is shown at DEBUG, as `--verbose` shows it.
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
            assertEquals("", stderr, "the dsl-tools readers report what they find through diagnostics, not through logging")
        }

    /**
     * What dsl-tools logs while a workspace analyzes is here the failure of a real reader. A KStates text within the
     * nesting limit nests deeper than the reader can follow on the small stack it reads with, [SmallStackDslReader].
     * The IDE's syntax analysis still follows it on the large stack the validation runs on. The failure is a
     * `reader.failure` in the report either way. dsl-tools also logs it as a warning that names the file. `--verbose`
     * shows that warning on standard error, and without it the warning stays off.
     */
    @Test
    fun `verbose shows what dsl-tools logs on standard error and leaves standard output to the report`() =
        DeepNesting.onStack(DeepNesting.LARGE_STACK) {
            runTest(timeout = 120.seconds) {
                val root = nestedContext()
                val workspaces = testWorkspaceFactory(this, SmallStackDslReader())

                val quiet = runCli(workspaces, "validate", root.toString())
                val verbose = runCli(workspaces, "validate", "--verbose", root.toString())

                assertEquals(ExitCodes.ERRORS_FOUND, quiet.status)
                val failure = quiet.lines.single { it.endsWith("[$READER_FAILURE]") }
                assertTrue(failure.startsWith("deep.kstates:1:1: error: The text could not be read"), failure)
                assertEquals("", quiet.stderr, "without verbose nothing of what dsl-tools logs is shown")
                assertEquals(quiet, verbose.copy(stderr = ""), "verbose changes standard error only")
                val logged = verbose.stderr.lines().filter { it.isNotEmpty() }
                val failed = "KstatesReader [${root.resolve("deep.kstates").toAbsolutePath().normalize()}] - $READER_LIMIT"
                assertTrue(logged.isNotEmpty(), "the reader's failure is logged")
                assertTrue(logged.all { DSL_TOOLS_LINE.containsMatchIn(it) && " WARN " in it && failed in it }, verbose.stderr)
                assertFalse(LoggerFactory.getLogger(DslToolsLoggers.PARENT).isErrorEnabled, "the verbose run turned dsl-tools off again")
            }
        }

    /**
     * A text that nests deeper than the nesting limit of the parsers is an error in the report, at the brace that opens
     * the first level too many. Standard error stays empty. Neither a stack trace nor what dsl-tools logs floods the
     * console, because nothing parses or reads the text.
     */
    @Test
    fun `a file nested deeper than the parser follows is an error at its brace and leaves standard error empty`() =
        DeepNesting.onStack(DEFAULT_STACK) {
            runTest(timeout = 120.seconds) {
                val root = deeplyNestedContext(depth = 20_000)

                val run = runCli(testWorkspaceFactory(this), "validate", root.toString())

                assertEquals(ExitCodes.ERRORS_FOUND, run.status)
                assertEquals(
                    listOf("deep.kstates:${firstBraceBeyondLimit(root.resolve("deep.kstates"))}: error: [${DiagnosticCodes.NESTING_TOO_DEEP}]"),
                    run.lines.filter { it.startsWith("deep.kstates:") }.map { it.withoutMessage() },
                )
                assertEquals("", run.stderr)
            }
        }

    /**
     * How deep the parsers and the readers of dsl-tools follow a text on the stack of a thread changes with the state
     * of the JIT compiler, so near that depth a folder used to validate differently from run to run. A folder within the
     * nesting limit validates clean and one beyond it with the same error, on every run, on the stack most JVMs give a
     * thread.
     */
    @Test
    fun `the same deeply nested folders validate the same way on every run`() = DeepNesting.onStack(DEFAULT_STACK) {
        runTest(timeout = 120.seconds) {
            val atLimit = deeplyNestedContext(NestingLimit.MAX_DEPTH - DeepNesting.STRUCTURE_DEPTH, folder = "at-limit")
            val beyond = deeplyNestedContext(depth = 4_000, folder = "beyond")
            val workspaces = testWorkspaceFactory(this)

            val atLimitRuns = List(RUNS) { runCli(workspaces, "validate", atLimit.toString()) }
            val beyondRuns = List(RUNS) { runCli(workspaces, "validate", "--format", "json", beyond.toString()) }

            assertEquals(List(RUNS) { CliRun(ExitCodes.OK, stdout = "", stderr = "") }, atLimitRuns)
            assertEquals(ExitCodes.ERRORS_FOUND, beyondRuns.first().status)
            assertTrue(DiagnosticCodes.NESTING_TOO_DEEP in beyondRuns.first().stdout, beyondRuns.first().stdout)
            assertEquals(List(RUNS) { beyondRuns.first() }, beyondRuns)
        }
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

    /**
     * A modeling context in [folder] of a metamodel and a state machine whose states nest [depth] levels deep, built by
     * [DeepNesting.kstates].
     */
    private fun deeplyNestedContext(depth: Int, folder: String = "deep"): Path =
        nestedContext(DeepNesting.kstates(depth), folder)

    /**
     * A modeling context in [folder] of a metamodel and the state machine [stateMachine], which [DeepNesting] makes
     * nest deep. By default its action nests [READER_DEPTH] guards deep, more than a dsl-tools reader follows on
     * [DeepNesting.SMALL_STACK].
     */
    private fun nestedContext(stateMachine: String = DeepNesting.guardedKstates(READER_DEPTH), folder: String = "deep"): Path {
        val root = Files.createDirectories(dir.resolve(folder))
        Files.writeString(root.resolve("deep.kmeta"), "type \"T\" \"t\" {\n}\n")
        Files.writeString(root.resolve("deep.kstates"), stateMachine)
        return root
    }

    /**
     * The one-based `line:col` of the opening brace of [kstates], a [DeepNesting.kstates] document, that opens the first
     * level beyond the nesting limit. That is the brace of the state at that level.
     */
    private fun firstBraceBeyondLimit(kstates: Path): String {
        val opening = "STATE \"s${NestingLimit.MAX_DEPTH - DeepNesting.STRUCTURE_DEPTH + 1}\" {"
        val text = Files.readString(kstates)
        val position = positionOf(text, opening)
        return "${position.substringBefore(':')}:${position.substringAfter(':').toInt() + opening.length - 1}"
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
        /**
         * How many guards [nestedContext] nests by default. That is too many for a dsl-tools reader on
         * [DeepNesting.SMALL_STACK], but within the nesting limit of the IDE's syntax analysis.
         */
        const val READER_DEPTH = NestingLimit.MAX_DEPTH - DeepNesting.GUARDS_STRUCTURE_DEPTH

        /** How often the determinism test validates each folder. */
        const val RUNS = 3

        /** The code of dsl-tools for a read that failed. */
        const val READER_FAILURE = "reader.failure"

        /** What dsl-tools logs when a text nests deeper than its reader can follow. */
        const val READER_LIMIT = "Reading a text failed, it nests deeper than the parser can recurse"

        /**
         * A line that the test configuration's console writes for an event of a dsl-tools logger, whose name the
         * console abbreviates to `i.k.d.` or `i.k.dsl.` followed by the rest.
         */
        val DSL_TOOLS_LINE = Regex("""^\S+ (TRACE|DEBUG|INFO|WARN|ERROR) +i\.k\.d(sl)?\.""")

        /** The stack that most JVMs give a thread by default, on which the parsers follow some thousand levels. */
        const val DEFAULT_STACK = 1024L * 1024

        /** A property declaration of the example metamodel. */
        const val VALID_PROPERTY = "prop(\"y\", \"number\")"

        /** [VALID_PROPERTY] without its comma, a syntax error. */
        const val PROPERTY_WITHOUT_COMMA = "prop(\"y\" \"number\")"

        /** `path:line:col: severity: message [code]`. */
        val REPORT_LINE = Regex("""^(.+:\d+:\d+): (error|warning|info|hint): (.*) \[([^\]]+)]$""")
    }
}
