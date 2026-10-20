plugins {
    id("karpfen.application")
}

application {
    mainClass = "io.karpfen.ide.bridges.runner.MainKt"
}

dependencies {
    implementation(project(":bridges:bridge-mqtt"))
    implementation(project(":bridges:bridge-ros"))
    runtimeOnly(libs.logback.classic)
}
