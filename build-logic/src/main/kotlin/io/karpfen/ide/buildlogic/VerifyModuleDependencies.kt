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
 * Fails when the module has a project dependency that violates [ModuleDependencyRules].
 *
 * Both inputs are plain lists of strings, which the `karpfen.kotlin-jvm` convention wires. So the task never touches the
 * project model while it runs, and it works with the configuration cache. [declaredProjectDependencies] holds the
 * project dependencies declared on every configuration of the module. They are scanned after all projects were
 * evaluated, so declarations in `afterEvaluate` are included. [resolvedProjectDependencies] holds the first-level
 * project dependencies of the module's resolved classpaths. They also contain dependencies added at resolution time,
 * through `withDependencies` or `defaultDependencies`.
 */
@DisableCachingByDefault(because = "Cheap check; up-to-date checking via the report file is sufficient")
abstract class VerifyModuleDependencies : DefaultTask() {

    /** The Gradle path of the module being verified, such as `:core:core-text`. */
    @get:Input
    abstract val projectPath: Property<String>

    /** The declared project dependencies, encoded with [ModuleDependencyRules.DeclaredDependency.encode]. */
    @get:Input
    abstract val declaredProjectDependencies: ListProperty<String>

    /**
     * The first-level project dependencies of the resolved classpaths. They are encoded like
     * [declaredProjectDependencies], but with the classpath configuration, such as `compileClasspath`, in place of the
     * declaring configuration.
     */
    @get:Input
    abstract val resolvedProjectDependencies: ListProperty<String>

    /** The report, written on success, so the task stays up to date until its inputs change. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * Writes the report if every project dependency is allowed.
     *
     * @throws GradleException listing every violation, if a declared or resolved project dependency is not allowed.
     */
    @TaskAction
    fun verify() {
        val from = projectPath.get()
        val dependencies = declaredProjectDependencies.get().map(ModuleDependencyRules.DeclaredDependency::decode)
        val resolved = resolvedProjectDependencies.get().map(ModuleDependencyRules.DeclaredDependency::decode)
        val violations = ModuleDependencyRules.violations(from, dependencies, resolved)
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module dependency rule violated in $from (see ModuleDependencyRules in build-logic):")
                    violations.forEach { appendLine("  - $it") }
                },
            )
        }
        reportFile.get().asFile.writeText(
            buildString {
                appendLine("$from: ${dependencies.size} declared project dependencies, all allowed")
                dependencies.forEach { appendLine("  ${it.configuration} -> ${it.targetPath}") }
                appendLine("resolved on the classpaths:")
                resolved.forEach { appendLine("  ${it.configuration} -> ${it.targetPath}") }
            },
        )
    }
}
