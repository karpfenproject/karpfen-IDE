package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Time-budget tests are never run: a test that asserts on a wall-clock measurement must carry the JUnit tag `budget`,
 * which every test task excludes, because machine load and sleep/wake cycles make the wall clock unreliable.
 * build-logic's `TestTags.BUDGET` declares the tag and explains the rule.
 *
 * The rule is a tripwire, not a proof. [TimingTests] judges every test function of every test source file:
 * [WallClockAssertions] decides whether it asserts on a wall-clock measurement, whatever the limit is compared with,
 * and the tag counts on the function or on a class around it, so an untagged timing test is caught even in a file
 * whose other tests carry the tag. Functional tests that wait for an event with a generous time limit, or only report
 * a measured duration, assert nothing on the wall clock and keep running.
 */
class BudgetTestTaggingTest {

    /** The verdicts on the tests of the test and test-fixture source files of all modules. */
    private val verdicts: List<TimingVerdict> by lazy {
        KarpfenModules.moduleFiles.filter { it.sourceSetName != "main" }.flatMap(TimingTests::verdicts)
    }

    @Test
    fun `tests that assert on wall-clock time are tagged budget`() {
        assertTrue(verdicts.isNotEmpty(), "No test functions found; is the Konsist scope broken?")
        assertEquals(
            emptyList(),
            verdicts.filter { it.assertsOnWallClock && !it.isTaggedBudget }.map { it.toString() },
            "These tests assert on wall-clock time but are not tagged budget, so every build would run them: " +
                "annotate the test function or its class with @Tag(\"budget\"), or remove the timing assertion",
        )
    }

    @Test
    fun `every test tagged budget is a timing test the tripwire recognizes`() {
        val tagged = verdicts.filter { it.isTaggedBudget }
        assertTrue(tagged.size >= PHASE_ONE_BUDGET_TESTS, "expected the budget tests of phase 1, found $tagged")
        assertEquals(
            emptyList(),
            tagged.filterNot { it.assertsOnWallClock }.map { it.toString() },
            "These tests are tagged budget, so no build runs them, but the tripwire sees no timing assertion in them: " +
                "move a functional test out of the budget tag, or teach the tripwire the new shape of timing assertion",
        )
    }

    @Test
    fun `an untagged timing test is caught in a file whose other tests are tagged`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("PlantedTimingTest.kt"), PLANTED_TESTS)
        val file = Konsist.scopeFromExternalDirectory(directory.toString()).files.single()

        assertEquals(
            listOf(
                Triple("tagged by its class", true, true),
                Triple("functional", false, false),
                Triple("tagged itself", true, true),
                Triple("untagged with a literal limit", false, true),
            ),
            TimingTests.verdicts(file).map { Triple(it.test, it.isTaggedBudget, it.assertsOnWallClock) },
        )
    }

    private companion object {
        /** The number of time-budget test functions written in phase 1, all of which the tripwire must recognize. */
        const val PHASE_ONE_BUDGET_TESTS = 14

        /** A test source file with a tagged class, and a class that mixes a functional, a tagged and an untagged test. */
        const val PLANTED_TESTS = """
            package planted

            import org.junit.jupiter.api.Tag
            import kotlin.test.Test
            import kotlin.test.assertTrue
            import kotlin.time.Duration.Companion.milliseconds
            import kotlin.time.measureTime

            @Tag("budget")
            class TaggedTimingTest {
                @Test
                fun `tagged by its class`() {
                    assertTrue(measureTime { work() } < 200.milliseconds)
                }
            }

            class MixedTimingTest {
                @Test
                fun `functional`() {
                    assertTrue(work() == 1)
                }

                @Test
                @Tag("budget")
                fun `tagged itself`() {
                    val elapsed = measureTime { work() }
                    assertTrue(elapsed < 200.milliseconds)
                }

                @Test
                fun `untagged with a literal limit`() {
                    val elapsed = measureTime { work() }
                    assertTrue(elapsed < 200.milliseconds)
                }
            }

            fun work(): Int = 1
        """
    }
}
