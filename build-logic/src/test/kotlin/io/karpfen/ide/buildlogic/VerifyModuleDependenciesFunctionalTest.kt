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
 * dependencies that are added late: in the module's own afterEvaluate block (after the convention plugin's callbacks)
 * and at resolution time through withDependencies. It runs with the configuration cache to prove that the inputs can be
 * stored.
 */
class VerifyModuleDependenciesFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    /** The shared version catalog; the test task runs in build-logic/ (see build-logic/build.gradle.kts). */
    private val versionCatalog: File = File("../gradle/libs.versions.toml").absoluteFile.normalize()

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
            // Registered after the convention plugin's callbacks.
            afterEvaluate { dependencies { "implementation"(project(":ui:ui-clean")) } }
            """,
        )
        write(
            "core/core-resolution/build.gradle.kts",
            """
            plugins { id("karpfen.kotlin-jvm") }
            val ui = dependencies.project(":ui:ui-clean")
            // Only added when a classpath that extends implementation is resolved.
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
