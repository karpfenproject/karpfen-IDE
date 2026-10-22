plugins {
    id("karpfen.application")
}

/**
 * The start script is named `karpfen-bridge-runner` (`build/install/karpfen-bridge-runner/bin/karpfen-bridge-runner`),
 * matching the usage line that the runner prints.
 */
application {
    mainClass = "io.karpfen.ide.bridges.runner.MainKt"
    applicationName = "karpfen-bridge-runner"
}

dependencies {
    implementation(project(":bridges:bridge-mqtt"))
    implementation(project(":bridges:bridge-ros"))
    runtimeOnly(libs.logback.classic)
}
