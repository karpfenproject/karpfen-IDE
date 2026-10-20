package io.karpfen.ide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails when the module declares a project dependency that violates [ModuleDependencyRules].
 *
 * The declared project dependencies are captured at configuration time as a plain list of strings
 * (see `karpfen.kotlin-jvm`), so the task never touches the project model while executing and is
 * compatible with the configuration cache.
 */
@DisableCachingByDefault(because = "Cheap check; up-to-date checking via the report file is sufficient")
abstract class VerifyModuleDependencies : DefaultTask() {

    /** Gradle path of the module being verified, e.g. `:core:core-text`. */
    @get:Input
    abstract val projectPath: Property<String>

    /** Declared project dependencies, encoded with [ModuleDependencyRules.DeclaredDependency.encode]. */
    @get:Input
    abstract val declaredProjectDependencies: ListProperty<String>

    /** Written on success so the task is up to date until its inputs change. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val from = projectPath.get()
        val dependencies = declaredProjectDependencies.get().map(ModuleDependencyRules.DeclaredDependency::decode)
        val violations = ModuleDependencyRules.violations(from, dependencies)
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module dependency rule violated in $from (see docs/adr/0008-module-boundaries-enforced-by-the-build.md):")
                    violations.forEach { appendLine("  - $it") }
                },
            )
        }
        reportFile.get().asFile.writeText(
            buildString {
                appendLine("$from: ${dependencies.size} project dependencies, all allowed")
                dependencies.forEach { appendLine("  ${it.configuration} -> ${it.targetPath}") }
            },
        )
    }
}
