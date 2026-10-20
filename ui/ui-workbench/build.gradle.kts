plugins {
    id("karpfen.compose")
}

dependencies {
    implementation(project(":ui:ui-editor"))
    implementation(project(":ui:ui-diagram"))
    implementation(project(":runtime:runtime-session"))
    implementation(project(":runtime:runtime-host"))
}
