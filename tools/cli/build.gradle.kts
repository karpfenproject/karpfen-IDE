plugins {
    id("karpfen.application")
}

application {
    mainClass = "io.karpfen.ide.tools.cli.MainKt"
    // Start script name (build/install/karpfen/bin/karpfen), matching the usage line.
    applicationName = "karpfen"
}

dependencies {
    implementation(project(":core:core-lang"))
    implementation(project(":runtime:runtime-session"))
    runtimeOnly(libs.logback.classic)
}
