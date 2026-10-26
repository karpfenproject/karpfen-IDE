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
 * and every test task excludes that tag, because machine load and sleep/wake cycles make the wall clock unreliable.
 * build-logic's `TestTags.BUDGET` declares the tag and explains the rule.
 *
 * The rule is a tripwire, not a proof. [TimingTests] judges every test function of every test source file, those of
 * build-logic included: [WallClockAssertions] decides whether it asserts on a wall-clock measurement, whatever the
 * limit is compared with, and the tag counts on the function or on a class around it, so an untagged timing test is
 * caught even in a file whose other tests carry the tag. Functional tests that wait for an event with a generous time
 * limit, or only report a measured duration, assert nothing on the wall clock and keep running.
 *
 * The test tasks are read from the Gradle scripts: every call of `useJUnitPlatform` must exclude the tag, by the
 * constant `TestTags.BUDGET` or by its value, which is checked against the declaration of the constant.
 */
class BudgetTestTaggingTest {

    /** The verdicts on the tests of the test and test-fixture source files of all modules and of build-logic. */
    private val verdicts: List<TimingVerdict> by lazy {
        (KarpfenModules.moduleFiles.filter { it.sourceSetName != "main" } + KarpfenModules.buildToolingTestFiles)
            .flatMap(TimingTests::verdicts)
    }

    @Test
    fun `the tests of build-logic are judged like those of the modules`() {
        assertTrue(verdicts.any { BUILD_LOGIC_TESTS in it.file.replace('\\', '/') }, "No test of build-logic was judged")
    }

    @Test
    fun `every test task excludes the budget tag`() {
        val declaration = DocumentedFiles.all.single { it.relativePath.endsWith("/buildlogic/TestTags.kt") }
        assertTrue(BUDGET_DECLARATION.containsMatchIn(declaration.text), "TestTags.BUDGET is no longer \"${TimingTests.BUDGET_TAG}\"")

        val platforms = DocumentedFiles.all.filter { it.relativePath.endsWith(".gradle.kts") }.flatMap { script ->
            val code = KotlinLexer.code(script.text)
            JUNIT_PLATFORM.findAll(code).map { call ->
                script.relativePath to script.text.textOf(CodeRegions.callArguments(code, call.range.last + 1))
            }
        }
        assertTrue(
            platforms.map { it.first }.containsAll(TEST_TASK_SCRIPTS),
            "The test tasks of build-logic or of the modules are no longer configured where expected; this check would be vacuous",
        )
        assertEquals(
            emptyList(),
            platforms.filterNot { (_, configuration) -> BUDGET_EXCLUSION.containsMatchIn(configuration) }.map { it.first },
            "These scripts run tests on the JUnit Platform without excluding the budget tag: write useJUnitPlatform { excludeTags(...) }",
        )
    }

    @Test
    fun `tests that assert on wall-clock time are tagged budget`() {
        assertTrue(verdicts.isNotEmpty(), "No test functions found; is the Konsist scope broken?")
        assertEquals(
            emptyList(),
            verdicts.filter { it.assertsOnWallClock && !it.isTaggedBudget }.map { it.toString() },
            "These tests assert on wall-clock time but are not tagged budget, so every build would run them. A time-budget " +
                "test gets @Tag(\"budget\") on the test function or its class; a functional test that waits for an event " +
                "writes the wait as a deadline or a generous timeout instead (WallClockAssertions lists what counts)",
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
    fun `an untagged timing test is caught in a file whose other tests are tagged, whichever way they are tagged`(
        @TempDir directory: Path,
    ) {
        Files.writeString(directory.resolve("PlantedTimingTest.kt"), PLANTED_TESTS)
        val file = Konsist.scopeFromExternalDirectory(directory.toString()).files.single()

        assertEquals(
            listOf(
                Triple("tagged by its class", true, true),
                Triple("functional", false, false),
                Triple("tagged itself", true, true),
                Triple("untagged with a literal limit", false, true),
                Triple("tagged by a named argument", true, true),
                Triple("tagged by the qualified annotation", true, true),
                Triple("tagged inside the container", true, true),
                Triple("untagged with a tight timeout", false, true),
            ),
            TimingTests.verdicts(file).map { Triple(it.test, it.isTaggedBudget, it.assertsOnWallClock) },
        )
    }

    private companion object {
        /** The number of time-budget test functions written in phase 1, all of which the tripwire must recognize. */
        const val PHASE_ONE_BUDGET_TESTS = 14

        /** The Gradle scripts that configure the test tasks of build-logic and of the modules. */
        val TEST_TASK_SCRIPTS = listOf("build-logic/build.gradle.kts", "build-logic/src/main/kotlin/karpfen.kotlin-jvm.gradle.kts")

        /** The directory of the test sources of build-logic. */
        const val BUILD_LOGIC_TESTS = "/build-logic/src/test/"

        /** The declaration of the budget tag in build-logic's `TestTags`, with the value the tripwire looks for. */
        val BUDGET_DECLARATION = Regex("""\bconst\s+val\s+BUDGET\s*:\s*String\s*=\s*"${TimingTests.BUDGET_TAG}"""")

        /** A call of `useJUnitPlatform` in a Gradle script, followed by its arguments or configuration block. */
        val JUNIT_PLATFORM = Regex("""\buseJUnitPlatform\b""")

        /** The exclusion of the budget tag, by the constant of build-logic or by its value. */
        val BUDGET_EXCLUSION = Regex("""\bexcludeTags\(\s*(?:TestTags\.BUDGET|"${TimingTests.BUDGET_TAG}")\s*\)""")

        /**
         * A test source file with a tagged class, and a class with a generous timeout that mixes a functional test,
         * tests tagged in each way JUnit reads the tag, and untagged timing tests, one of them by a tight timeout.
         */
        const val PLANTED_TESTS = """
            package planted

            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Tags
            import org.junit.jupiter.api.Timeout
            import java.util.concurrent.TimeUnit
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

            @Timeout(value = 90, unit = TimeUnit.SECONDS)
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

                @Test
                @Tag(value = "budget")
                fun `tagged by a named argument`() {
                    measureTime { work() }.let { assertTrue(it < 200.milliseconds) }
                }

                @Test
                @org.junit.jupiter.api.Tag("budget")
                fun `tagged by the qualified annotation`() {
                    assertTrue(measureTime { work() } < 200.milliseconds)
                }

                @Test
                @Tags(Tag("budget"), Tag("slow"))
                fun `tagged inside the container`() {
                    assertTrue(measureTime { work() } < 200.milliseconds)
                }

                @Test
                @Timeout(100, unit = TimeUnit.MILLISECONDS)
                fun `untagged with a tight timeout`() {
                    assertTrue(work() == 1)
                }
            }

            fun work(): Int = 1
        """
    }
}
