package io.karpfen.ide.tools.cli

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `karpfen validate` on deterministic random mutations of a modeling context (fixed seed): it always answers 0 or 1,
 * prints only well-formed report lines whose one-based positions lie inside the reported file, writes nothing to
 * stderr, and exits 1 exactly when a line is an error.
 */
class ValidateRobustnessTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `mutated context files never break the report or leak console output`(reporter: TestReporter) = runTest(timeout = 300.seconds) {
        val files = Examples.files().filter { it.directory == DescriptorExamples.CONTEXT_DIRECTORY }
        files.forEach { Files.writeString(dir.resolve(it.fileName), it.text) }
        var runs = 0
        val mark = TimeSource.Monotonic.markNow()

        for (file in files) {
            for (mutation in Mutations.random(file.text, seed = SEED, count = MUTATIONS_PER_FILE)) {
                Files.writeString(dir.resolve(file.fileName), mutation.text)
                val run = runCli(testWorkspaceFactory(this), "validate", dir.toString())
                val context = "${file.fileName} mutation ${mutation.index} ${mutation.operations}"

                assertTrue(run.status == ExitCodes.OK || run.status == ExitCodes.ERRORS_FOUND, "$context: status ${run.status}")
                assertEquals("", run.stderr, context)
                run.lines.forEach { line -> assertWellFormed(line, context) }
                assertEquals(run.lines.any { ": error: " in it }, run.status == ExitCodes.ERRORS_FOUND, context)
                runs++
            }
            Files.writeString(dir.resolve(file.fileName), file.text)
        }

        reporter.publishEntry("karpfen validate on mutated single-robot contexts", "$runs runs in ${mark.elapsedNow()}")
    }

    /** `path:line:col: severity: message [code]` with the position inside the file's current text. */
    private fun assertWellFormed(line: String, context: String) {
        val match = requireNotNull(REPORT_LINE.matchEntire(line)) { "$context: malformed report line '$line'" }
        val (path, lineNumber, column) = match.destructured
        val lines = Files.readString(dir.resolve(path)).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val row = lineNumber.toInt()
        assertTrue(row in 1..lines.size, "$context: line $row of ${lines.size} in '$line'")
        assertTrue(column.toInt() in 1..lines[row - 1].length + 1, "$context: column $column in '$line'")
    }

    private companion object {
        /** The fixed seed of the mutations. */
        const val SEED = 20261021L

        /** How many mutated versions of each context file are validated. */
        const val MUTATIONS_PER_FILE = 12

        /** A well-formed report line, `path:line:col: severity: message [code]`, capturing path, line and column. */
        val REPORT_LINE = Regex("""^([^:]+):(\d+):(\d+): (error|warning|info|hint): .* \[[^\]]+]$""")
    }
}
