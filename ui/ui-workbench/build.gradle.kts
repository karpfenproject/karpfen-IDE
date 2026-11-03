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
/** Compose for the views, and kotlinx.serialization for the `IdeSettings`, which are stored as JSON. */
plugins {
    id("karpfen.compose")
    id("karpfen.kotlin-serialization")
}

/**
 * The editor, the UI foundation and the language services are `api` dependencies because the public `WorkbenchState`
 * exposes `Workspace`, `LanguageServices`, `CodeEditorState` and `CommandRegistry`. The runtime session is the base of
 * the later runtime tool window; the diagram module and runtime-host are still phase-0 placeholders for the diagram and
 * the runtime hosting of later phases.
 */
dependencies {
    api(project(":ui:ui-editor"))
    api(project(":ui:ui-foundation"))
    api(project(":core:core-lang"))
    implementation(libs.slf4j.api)

    implementation(project(":ui:ui-diagram"))
    implementation(project(":runtime:runtime-session"))
    implementation(project(":runtime:runtime-host"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
