/** Compose for the views, and kotlinx.serialization for the `IdeSettings`, which are stored as JSON. */
plugins {
    id("karpfen.compose")
    id("karpfen.kotlin-serialization")
}

/**
 * The editor, the UI foundation and the language services are `api` dependencies because the public `WorkbenchState`
 * exposes `Workspace`, `LanguageServices`, `CodeEditorState` and `CommandRegistry`. The diagram and runtime modules are
 * phase-0 placeholders for the diagram and runtime tool windows of later phases.
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
