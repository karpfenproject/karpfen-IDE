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
import io.karpfen.ide.buildlogic.LiveDockerTesting
import io.karpfen.ide.buildlogic.LiveRuntimeTesting
import io.karpfen.ide.buildlogic.ModuleDependencyRules
import io.karpfen.ide.buildlogic.TestTags
import io.karpfen.ide.buildlogic.ResolvedProjectDependencies
import io.karpfen.ide.buildlogic.VerifyModuleDependencies
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

/**
 * Base convention for every karpfen IDE module: Kotlin/JVM on JDK 21, JUnit Platform tests without the time-budget
 * tests and without the tests against a real runtime, explicit API mode for the UI-agnostic layers and the runtime
 * modules, and the check of the module dependency rules. The other karpfen.* convention plugins build on it, and the
 * settings reject a module that does not apply it.
 */
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

group = "io.karpfen.ide"
version = "0.1.0-SNAPSHOT"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Compiles for JDK 21. The core and notation modules are the reusable, UI-agnostic layers, and the runtime modules
 * are the client of the karpfen runtime that the bridges build on too, so they compile in explicit API mode: every
 * public declaration is deliberate.
 */
kotlin {
    jvmToolchain(21)
    if (path.startsWith(":core:") || path.startsWith(":notation:") || path.startsWith(":runtime:")) {
        explicitApi()
    }
}

/**
 * Aligns kotlinx.coroutines and kotlinx.serialization through their BOMs, so all modules and their tests use one
 * version of each, whatever the transitive graph of a module pulls in, and gives every module the shared test bundle.
 */
dependencies {
    implementation(platform(libs.findLibrary("kotlinx-coroutines-bom").get()))
    implementation(platform(libs.findLibrary("kotlinx-serialization-bom").get()))

    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findBundle("testing").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

/**
 * Runs the tests on the JUnit Platform and reports failures with their full stack traces. Every test task excludes the
 * time-budget tests ([TestTags.BUDGET]), and [TestTags] explains why they never run. The tests against a real runtime
 * ([TestTags.LIVE_RUNTIME]) are excluded from every test task except the live task of the runtime modules, of
 * ui-workbench and of the IDE application, [LiveRuntimeTesting.TASK_NAME]. The tests against the local Docker engine ([TestTags.LIVE_DOCKER]) are excluded from
 * every test task except runtime-host's [LiveDockerTesting.TASK_NAME]. A live task must not inherit the exclusion of its
 * own tag, or it would find no test.
 */
tasks.withType<Test>().configureEach {
    val runsLiveRuntimeTests = name == LiveRuntimeTesting.TASK_NAME
    val runsLiveDockerTests = name == LiveDockerTesting.TASK_NAME
    useJUnitPlatform {
        excludeTags(TestTags.BUDGET)
        if (!runsLiveRuntimeTests) {
            excludeTags(TestTags.LIVE_RUNTIME)
        }
        if (!runsLiveDockerTests) {
            excludeTags(TestTags.LIVE_DOCKER)
        }
    }
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

/** The Gradle path of this module, captured here because inside a task's configuration `path` names the task. */
val modulePath: String = path

/**
 * Checks the project dependencies of this module against [ModuleDependencyRules]. The task gets two plain lists of
 * strings, so it never touches the project model and never accesses another project. One list holds the project
 * dependencies declared on the module's configurations. The other holds the first-level project dependencies of its
 * resolved classpaths. Both are wired below.
 */
val verifyModuleDependencies = tasks.register<VerifyModuleDependencies>("verifyModuleDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the project dependencies against the module dependency matrix."
    projectPath = modulePath
    reportFile = layout.buildDirectory.file("reports/module-dependencies/$name.txt")
}

/**
 * Hands the project dependencies declared on every configuration to [verifyModuleDependencies], not only those of
 * api, implementation, compileOnly, runtimeOnly and their test variants: a project dependency declared on a custom
 * configuration that a classpath extends from, or on annotationProcessor, kapt or ksp, is checked as well.
 * `dependencies` holds what is declared on that configuration itself, so inherited declarations are not reported twice.
 * The scan runs in `projectsEvaluated`, that is after every `afterEvaluate` callback, including those of the module's
 * own build script.
 */
gradle.projectsEvaluated {
    val declaredProjectDependencies: List<String> = configurations
        .flatMap { configuration ->
            configuration.dependencies.withType<ProjectDependency>().map { dependency ->
                ModuleDependencyRules.DeclaredDependency(configuration.name, dependency.path).encode()
            }
        }
        .sorted()
    verifyModuleDependencies.configure {
        this.declaredProjectDependencies.set(declaredProjectDependencies)
    }
}

/**
 * Hands the first-level project dependencies of the resolved compile and runtime classpaths of every source set to
 * [verifyModuleDependencies], as providers of the resolution result. They are evaluated when the configuration cache
 * entry is stored, or when the task runs without the configuration cache, so they also see dependencies added at
 * resolution time (`withDependencies`, `defaultDependencies`) and any declaration that the scan of the declared
 * dependencies did not see.
 */
sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { classpath ->
        val firstLevelProjectDependencies: Provider<List<String>> = configurations.named(classpath)
            .flatMap { it.incoming.resolutionResult.rootComponent }
            .map { root -> ResolvedProjectDependencies.firstLevel(classpath, root) }
        verifyModuleDependencies.configure {
            resolvedProjectDependencies.addAll(firstLevelProjectDependencies)
        }
    }
}

/** Makes `check` run the dependency check, so `./gradlew build` fails on a forbidden project dependency. */
tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyModuleDependencies)
}
