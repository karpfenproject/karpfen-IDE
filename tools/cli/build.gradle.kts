plugins {
    id("karpfen.application")
    // `validate --format json` (spec §9).
    id("karpfen.kotlin-serialization")
}

application {
    mainClass = "io.karpfen.ide.tools.cli.MainKt"
    // Start script name (build/install/karpfen/bin/karpfen), matching the usage line.
    applicationName = "karpfen"
}

dependencies {
    // validate: workspace, project analysis and per-file tokenizing/parsing (core-workspace api core-document).
    implementation(project(":core:core-workspace"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    // Phase-0 placeholder for the later `deploy` command.
    implementation(project(":runtime:runtime-session"))
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
