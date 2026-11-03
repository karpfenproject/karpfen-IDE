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
 * Kotlin/JVM with shared test fixtures: workspaces on virtual time, a file watcher that tests drive, and the helpers to
 * open documents and let virtual time pass, which the tests of this module use and the tests of downstream modules,
 * such as the runtime session, reach through `testFixtures(project(":core:core-workspace"))`.
 */
plugins {
    id("karpfen.kotlin-jvm")
    `java-test-fixtures`
}

/**
 * The workspace hands out documents, sessions, diagnostics and the dsl-tools models of the project analysis, all from
 * core-document, which also brings kotlinx.serialization, the reader of environment descriptors. The test fixtures
 * expose kotlinx-coroutines-test, whose test scope and dispatchers their public functions take.
 */
dependencies {
    api(project(":core:core-document"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
    testFixturesApi(libs.kotlinx.coroutines.test)
}
