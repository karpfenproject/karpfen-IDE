plugins {
    id("karpfen.compose")
}

dependencies {
    // Public API: KeyStroke/KeyShortcut/HostPlatform in the keymap and completion model (ui-foundation), and
    // LanguageServices/Location plus DocumentSession, EditableText and diagnostics (core-lang and, through its api
    // chain, core-workspace, core-document and core-text) in CodeEditorState.
    api(project(":ui:ui-foundation"))
    api(project(":core:core-lang"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    // Example files and the synthetic 10,000-line KStates document for the rendering benchmark (spec §7.2).
    testImplementation(testFixtures(project(":core:core-document")))
}
