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
/** An application with a start script, and kotlinx.serialization for `validate --format json`. */
plugins {
    id("karpfen.application")
    id("karpfen.kotlin-serialization")
}

/** The start script is named `karpfen` (`build/install/karpfen/bin/karpfen`), matching the usage line. */
application {
    mainClass = "io.karpfen.ide.tools.cli.MainKt"
    applicationName = "karpfen"
}

/**
 * `validate` needs the workspace, the project analysis and the per-file tokenizing and parsing (core-workspace has
 * core-document as an `api` dependency). The runtime session is a phase-0 placeholder for the later `deploy` command.
 * logback is the logging backend, whose API `LogbackLanguageToolsLog` uses to show what dsl-tools logs with
 * `--verbose`.
 */
dependencies {
    implementation(project(":core:core-workspace"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(project(":runtime:runtime-session"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
