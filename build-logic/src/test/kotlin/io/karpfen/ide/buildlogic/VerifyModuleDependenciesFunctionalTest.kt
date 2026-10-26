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

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Applies `karpfen.kotlin-jvm` to a small fixture build and proves that verifyModuleDependencies also sees project
 * dependencies that are added late. In the fixture, `core-late` declares its forbidden dependency on a UI module in its
 * own `afterEvaluate` block, which is registered after the convention plugin's callbacks; `core-resolution` adds it
 * through `withDependencies`, so it only appears when a classpath that extends `implementation` is resolved;
 * `core-clean` and `ui-clean` follow the rules. It runs with the configuration cache to prove that the inputs can be
 * stored.
 */
class VerifyModuleDependenciesFunctionalTest {

    /** The root directory of the fixture build, a fresh temporary directory for every test. */
    @TempDir
    lateinit var projectDir: File

    /** The shared version catalog; the test task runs in build-logic/ (see build-logic/build.gradle.kts). */
    private val versionCatalog: File = File("../gradle/libs.versions.toml").absoluteFile.normalize()

    /** Writes [content], without its common indentation, to [path] below the fixture directory. */
    private fun write(path: String, content: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs() }.writeText(content.trimIndent() + "\n")
    }

    @Test
    fun `late project dependencies are checked`() {
        assertTrue(versionCatalog.isFile, "Version catalog not found at $versionCatalog")
        write(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs { create("libs") { from(files("${versionCatalog.invariantSeparatorsPath}")) } }
            }
            rootProject.name = "matrix-fixture"
            include(":core:core-late", ":core:core-resolution", ":core:core-clean", ":ui:ui-clean")
            """,
        )
        write(
            "core/core-late/build.gradle.kts",
            """
            plugins { id("karpfen.kotlin-jvm") }
            afterEvaluate { dependencies { "implementation"(project(":ui:ui-clean")) } }
            """,
        )
        write(
            "core/core-resolution/build.gradle.kts",
            """
            plugins { id("karpfen.kotlin-jvm") }
            val ui = dependencies.project(":ui:ui-clean")
            configurations.named("implementation") { withDependencies { add(ui) } }
            """,
        )
        write("core/core-clean/build.gradle.kts", """plugins { id("karpfen.kotlin-jvm") }""")
        write(
            "ui/ui-clean/build.gradle.kts",
            """
            plugins { id("karpfen.kotlin-jvm") }
            dependencies { implementation(project(":core:core-clean")) }
            """,
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("verifyModuleDependencies", "--continue", "--configuration-cache", "--stacktrace")
            .buildAndFail()
        val output = result.output

        assertEquals(TaskOutcome.FAILED, result.task(":core:core-late:verifyModuleDependencies")?.outcome, output)
        assertTrue(output.contains("implementation(project(\":ui:ui-clean\")) is not allowed"), output)
        assertEquals(TaskOutcome.FAILED, result.task(":core:core-resolution:verifyModuleDependencies")?.outcome, output)
        assertTrue(output.contains("project(\":ui:ui-clean\") on compileClasspath, runtimeClasspath"), output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":core:core-clean:verifyModuleDependencies")?.outcome, output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":ui:ui-clean:verifyModuleDependencies")?.outcome, output)
        assertTrue(output.contains("Configuration cache entry stored"), output)
    }
}
