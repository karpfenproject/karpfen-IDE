plugins {
    id("karpfen.application")
}

application {
    mainClass = "io.karpfen.ide.bridges.runner.MainKt"
    // Start script name (build/install/karpfen-bridge-runner/bin/karpfen-bridge-runner), matching the usage line.
    applicationName = "karpfen-bridge-runner"
}

dependencies {
    implementation(project(":bridges:bridge-mqtt"))
    implementation(project(":bridges:bridge-ros"))
    runtimeOnly(libs.logback.classic)
}
