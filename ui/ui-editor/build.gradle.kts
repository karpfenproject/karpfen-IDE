plugins {
    id("karpfen.compose")
}

dependencies {
    /**
     * Part of the public API: `KeyStroke`, `KeyShortcut` and `HostPlatform` in the keymap and the completion model
     * (ui-foundation), and `LanguageServices`, `Location`, `DocumentSession`, `EditableText` and the diagnostics in
     * `CodeEditorState` (core-lang and, through its api chain, core-workspace, core-document and core-text).
     */
    api(project(":ui:ui-foundation"))
    api(project(":core:core-lang"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    /** The example files and the synthetic 10,000-line KStates document of the rendering benchmark. */
    testImplementation(testFixtures(project(":core:core-document")))
}
