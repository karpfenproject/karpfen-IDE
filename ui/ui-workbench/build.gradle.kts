plugins {
    id("karpfen.compose")
    // IdeSettings are stored as JSON (spec §8).
    id("karpfen.kotlin-serialization")
}

dependencies {
    // Public API: WorkbenchState exposes Workspace, LanguageServices, CodeEditorState and CommandRegistry.
    api(project(":ui:ui-editor"))
    api(project(":ui:ui-foundation"))
    api(project(":core:core-lang"))
    implementation(libs.slf4j.api)

    // Phase-0 placeholders for later phases (diagrams, runtime tool windows).
    implementation(project(":ui:ui-diagram"))
    implementation(project(":runtime:runtime-session"))
    implementation(project(":runtime:runtime-host"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
