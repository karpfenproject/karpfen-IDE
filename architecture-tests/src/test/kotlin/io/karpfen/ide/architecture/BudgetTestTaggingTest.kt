package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Time-budget tests are never run: a test that compares a wall-clock duration with a limit must carry the JUnit tag
 * `budget`, which every test task excludes, because machine load and sleep/wake cycles make the wall clock unreliable.
 * build-logic's `TestTags.BUDGET` declares the tag and explains the rule.
 *
 * The rule is a tripwire, not a proof: a test source file whose code reads the wall clock ([WALL_CLOCK]) and names a
 * limit ([LIMIT_NAME]: an identifier containing budget, slack or guard) must declare `@Tag("budget")` on the class or
 * on a test function. Comments, string literals and backtick test names are ignored, so a functional test that merely
 * talks about a production time limit (e.g. a search that gives up after its budget) is not caught.
 */
class BudgetTestTaggingTest {

    private companion object {
        /** A read of the wall clock: a monotonic time mark, a measuring function or the system clock. */
        val WALL_CLOCK = Regex("""\b(TimeSource\.Monotonic|measureTimedValue|measureTime|measureNanoTime|measureTimeMillis|System\.nanoTime|System\.currentTimeMillis)\b""")

        /** An identifier that names a time limit: it contains budget, slack or guard, in any case. */
        val LIMIT_NAME = Regex("""\b\w*(budget|slack|guard)\w*\b""", RegexOption.IGNORE_CASE)

        /** A backtick identifier, such as a test name, which may talk about budgets without measuring anything. */
        val BACKTICK_NAME = Regex("""`[^`\n]*`""")

        /** The annotation itself, at the start of a line (so documentation that mentions it does not count). */
        val BUDGET_TAG = Regex("""(?m)^\s*@Tag\("budget"\)""")

        /**
         * True if the code of [source], without comments, literals and backtick identifiers, both reads the wall clock
         * and names a limit.
         */
        fun comparesWallClockWithLimit(source: String): Boolean {
            val code = SourceReferences.blankCommentsAndLiterals(source).replace(BACKTICK_NAME, " ")
            return WALL_CLOCK.containsMatchIn(code) && LIMIT_NAME.containsMatchIn(code)
        }
    }

    /** The test and test-fixture source files of all modules. */
    private val testFiles: List<KoFileDeclaration> by lazy { KarpfenModules.moduleFiles.filter { it.sourceSetName != "main" } }

    @Test
    fun `tests that compare wall-clock time with a limit are tagged budget`() {
        assertTrue(testFiles.isNotEmpty(), "No test files found; is the Konsist scope broken?")
        val untagged = testFiles.filter { comparesWallClockWithLimit(it.text) && !BUDGET_TAG.containsMatchIn(it.text) }
        assertEquals(
            emptyList(),
            untagged.map { it.path },
            "These tests compare wall-clock time with a limit but are not tagged budget, so every build would run them: " +
                "annotate the class or the test functions with @Tag(\"budget\"), or remove the timing assertion",
        )
    }

    @Test
    fun `the rule sees the existing budget tests`() {
        val tagged = testFiles.filter { BUDGET_TAG.containsMatchIn(it.text) }
        assertTrue(tagged.size >= 8, "expected the 8 budget test classes of phase 1, found ${tagged.map { it.name }}")
        assertEquals(emptyList(), tagged.filterNot { comparesWallClockWithLimit(it.text) }.map { it.name }, "tagged files the tripwire does not recognize")
    }

    @Test
    fun `the tripwire separates timing assertions from functional tests that mention a time limit`() {
        val timing = """
            val elapsed = TimeSource.Monotonic.markNow().elapsedNow()
            assertTrue(elapsed < BUDGET * SLACK)
        """
        val functional = """
            // the search stops at its budget
            fun `a search that exceeds its budget ends`() {
                val deadline = System.nanoTime() + 10_000
                assertEquals(TOO_SLOW, error, "stopped at its budget")
            }
        """
        assertTrue(comparesWallClockWithLimit(timing))
        assertTrue(!comparesWallClockWithLimit(functional))
    }
}
