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
