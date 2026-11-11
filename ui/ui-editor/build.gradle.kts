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
plugins {
    id("karpfen.compose")
}

/**
 * Both project dependencies are api dependencies because their types appear in the public API of this module. The
 * keymap and the completion model expose `KeyStroke`, `KeyShortcut` and `HostPlatform` from ui-foundation.
 * `CodeEditorState` exposes `LanguageServices`, `Location`, `DocumentSession`, `EditableText` and the diagnostics.
 * These come from core-lang, which in turn exposes core-workspace, core-document and core-text as api dependencies.
 * The tests read the example files and the synthetic 10,000-line KStates document of the rendering benchmark from the
 * test fixtures of core-document.
 */
dependencies {
    api(project(":ui:ui-foundation"))
    api(project(":core:core-lang"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
