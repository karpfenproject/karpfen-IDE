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
import io.karpfen.ide.buildlogic.GitSubmodules

plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

/**
 * The checkouts of other repositories in git submodules, as build-logic's `GitSubmodules` reads them from `.gitmodules`.
 * The architecture tests leave them out of the code they check, since those follow their own repository's rules.
 */
val submoduleDirectories: List<String> =
    GitSubmodules.parse(providers.fileContents(layout.settingsDirectory.file(".gitmodules")).asText.orNull.orEmpty())

/**
 * build-logic's `GitSubmodules`, copied below the build directory and compiled into the architecture tests, so that the
 * tests read the submodule directories that the build hands them with the code that writes them: the system property
 * and its format have one declaration.
 */
val gitSubmodulesSource = tasks.register<Sync>("copyGitSubmodulesSource") {
    from(layout.settingsDirectory.file("build-logic/src/main/kotlin/io/karpfen/ide/buildlogic/GitSubmodules.kt"))
    into(layout.buildDirectory.dir("generated/build-logic/kotlin"))
}

/** The test sources include the copy of build-logic's `GitSubmodules`. */
kotlin.sourceSets.named("test") {
    kotlin.srcDir(gitSubmodulesSource)
}

/**
 * The files at the root of the repository that the architecture tests read besides the sources and documents, as
 * `RootFiles` of the tests names them: `PackageLayoutTest` reads `.gitignore` and `SubmodulesTest` `.gitmodules`. They
 * are declared one by one, because a file tree leaves out the files of git by Gradle's default excludes.
 */
val rootFilesRead: List<String> = listOf(".gitignore", ".gitmodules")

/**
 * The architecture tests read files of the whole repository from disk: Konsist reads the Kotlin sources of all
 * modules, `DocumentationStyleTest` every Kotlin source file and Gradle script, build-logic's included, and the
 * markdown documents, CI workflows, XML resources and version catalog, `PackageLayoutTest` looks for Java sources, and
 * the tests read the [rootFilesRead]. All of these files are declared as inputs, so the architecture tests rerun, and
 * are never served stale from the build cache, when any of them changes. Build output, the `build` directories of the
 * root, of build-logic and of the modules, hidden directories other than `.github`, and the [submoduleDirectories] are
 * left out, as the tests skip them; a source package named `build` is an input like any other. The tests get the same
 * submodule directories through a system property, so the inputs and the tests never disagree on them.
 */
tasks.test {
    systemProperty(GitSubmodules.SYSTEM_PROPERTY, GitSubmodules.encode(submoduleDirectories))
    inputs.files(
        fileTree(layout.settingsDirectory) {
            include(
                "**/src/**/*.kt", "**/src/**/*.kts", "**/src/**/*.java", "**/*.gradle.kts", "**/*.md",
                "**/src/**/resources/**/*.xml", "gradle/*.toml",
            )
            exclude("build/**", "*/build/**", "*/*/build/**", "**/.*/**")
            submoduleDirectories.forEach { exclude("$it/**") }
        },
        fileTree(layout.settingsDirectory.dir(".github")),
        rootFilesRead.map { layout.settingsDirectory.file(it) },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleSources")
}
