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
/**
 * build-logic is an included build that holds the karpfen.* convention plugins of the modules. They are precompiled
 * script plugins in the Kotlin DSL, next to plain Kotlin classes for the tasks, rules and names they share.
 */
plugins {
    `kotlin-dsl`
}

/**
 * The plugins that the karpfen.* convention plugins apply, and JUnit for the tests of the module dependency rules. The
 * plugin versions come from the shared version catalog, so the modules never declare plugin versions themselves.
 */
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.compose.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Runs the tests on the JUnit Platform without the time-budget tests, like every test task of the modules. The tag is
 * written out here because a build script cannot use the classes of its own build. It is the value of
 * `TestTags.BUDGET`, and the architecture test `BudgetTestTaggingTest` checks that every test task of the repository
 * excludes that tag. The functional tests build fixtures with TestKit that read the shared version catalog relative to
 * the working directory, which is this project's directory. The catalog is therefore a declared input, and a change to
 * it reruns the tests.
 */
tasks.test {
    useJUnitPlatform {
        excludeTags("budget")
    }
    inputs.file(layout.projectDirectory.file("../gradle/libs.versions.toml"))
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("versionCatalog")
}
