plugins {
    id("karpfen.application")
}

application {
    mainClass = "io.karpfen.ide.tools.cli.MainKt"
}

dependencies {
    implementation(project(":core:core-lang"))
    implementation(project(":runtime:runtime-session"))
    runtimeOnly(libs.logback.classic)
}
