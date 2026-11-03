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
package io.karpfen.ide.buildlogic

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Applies `karpfen.kotlin-jvm` and `karpfen.live-runtime-test` to a small fixture module with three tests, one
 * untagged, one tagged live-runtime and one tagged budget, and proves the wiring of the live tag: the ordinary `test`
 * task runs only the untagged test, `check` never includes the live task, the live task without a runtime URL fails
 * before any test runs with the message that names the property and the script, and with a URL it runs exactly the live
 * test, which reads the URL from its system property (it never connects). The fixture tests are written in Java, so the
 * fixture build needs no Kotlin compiler. Every run uses the configuration cache to prove that the live task can be
 * stored.
 */
class LiveRuntimeTestFunctionalTest {

    /** The root directory of the fixture build, a fresh temporary directory for every test. */
    @TempDir
    lateinit var projectDir: File

    /** The shared version catalog; the test task runs in build-logic/ (see build-logic/build.gradle.kts). */
    private val versionCatalog: File = File("../gradle/libs.versions.toml").absoluteFile.normalize()

    /** Writes [content], without its common indentation, to [path] below the fixture directory. */
    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs() }.writeText(content.trimIndent() + "\n")
    }

    /** Writes the fixture build: one runtime module with the two plugins and the three tests. */
    private fun writeFixture() {
        assertTrue(versionCatalog.isFile, "Version catalog not found at $versionCatalog")
        write(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs { create("libs") { from(files("${versionCatalog.invariantSeparatorsPath}")) } }
            }
            rootProject.name = "live-fixture"
            include(":runtime:runtime-fixture")
            """,
        )
        write(
            "runtime/runtime-fixture/build.gradle.kts",
            """
            plugins {
                id("karpfen.kotlin-jvm")
                id("karpfen.live-runtime-test")
            }
            """,
        )
        write(
            "runtime/runtime-fixture/src/test/java/fixture/FixtureTests.java",
            """
            package fixture;

            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;

            public class FixtureTests {
                @Test
                public void untagged() {
                }

                @Test
                @Tag("live-runtime")
                public void live() {
                    Assertions.assertEquals("$FIXTURE_URL", System.getProperty("${LiveRuntimeTesting.URL_PROPERTY}"));
                }

                @Test
                @Tag("budget")
                public void budget() {
                }
            }
            """,
        )
    }

    /** A runner of the fixture build with the plugins of build-logic and the configuration cache. */
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    /**
     * The names of the test methods that the task [taskName] of the fixture module executed, from its XML results,
     * without the parentheses that the reports add to Java method names.
     */
    private fun executedTests(taskName: String): List<String> {
        val results = projectDir.resolve("runtime/runtime-fixture/build/test-results/$taskName")
        val reports = results.listFiles { file -> file.name.endsWith(".xml") }.orEmpty()
        return reports.flatMap { report -> TEST_CASE.findAll(report.readText()).map { it.groupValues[1] }.toList() }.sorted()
    }

    @Test
    fun `the test task runs only the untagged test and check never runs the live task`() {
        writeFixture()

        val test = runner(":runtime:runtime-fixture:test").build()
        val check = runner(":runtime:runtime-fixture:check", "--dry-run").build()

        assertEquals(TaskOutcome.SUCCESS, test.task(":runtime:runtime-fixture:test")?.outcome, test.output)
        assertEquals(listOf("untagged"), executedTests("test"), test.output)
        assertFalse(check.output.contains(LiveRuntimeTesting.TASK_NAME), check.output)
    }

    @Test
    fun `the live task fails before any test without a runtime URL and runs exactly the live test with one`() {
        writeFixture()

        val missing = runner(LIVE_TASK).buildAndFail()
        assertMessageNamesTheProperty(missing)
        assertEquals(emptyList<String>(), executedTests(LiveRuntimeTesting.TASK_NAME), missing.output)

        val malformed = runner(LIVE_TASK, "-P${LiveRuntimeTesting.URL_PROPERTY}=127.0.0.1:1").buildAndFail()
        assertMessageNamesTheProperty(malformed)

        val live = runner(LIVE_TASK, "-P${LiveRuntimeTesting.URL_PROPERTY}=$FIXTURE_URL").build()
        assertEquals(TaskOutcome.SUCCESS, live.task(LIVE_TASK)?.outcome, live.output)
        assertEquals(listOf("live"), executedTests(LiveRuntimeTesting.TASK_NAME), live.output)
    }

    /** Asserts that [result] failed with the message of `LiveRuntimeTarget`. */
    private fun assertMessageNamesTheProperty(result: BuildResult) {
        assertEquals(TaskOutcome.FAILED, result.task(LIVE_TASK)?.outcome, result.output)
        assertTrue(result.output.contains(MISSING_URL_MESSAGE), result.output)
    }

    private companion object {
        /** The live task of the fixture module. */
        const val LIVE_TASK: String = ":runtime:runtime-fixture:${LiveRuntimeTesting.TASK_NAME}"

        /** A runtime URL that nothing listens on; the live fixture test only reads it. */
        const val FIXTURE_URL: String = "http://127.0.0.1:1"

        /** The message of a live task without a usable runtime URL. */
        const val MISSING_URL_MESSAGE: String =
            "liveRuntimeTest needs -Pkarpfen.runtime.url=http://<host>:<port>; " +
                "runtime/live-runtime.sh test starts a runtime in Docker and passes it"

        /** A test case of a JUnit XML report, with the name of its method. */
        val TEST_CASE = Regex("""<testcase name="([^"(]+)""")
    }
}
