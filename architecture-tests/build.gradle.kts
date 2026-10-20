plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

tasks.test {
    // Konsist reads the Kotlin sources of all modules from disk. Declare them as inputs so the
    // architecture tests rerun (and are never served stale from the build cache) when any module changes.
    inputs.files(
        fileTree(layout.settingsDirectory) {
            include("**/src/**/*.kt")
            exclude("build-logic/**", "**/build/**", ".*/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleSources")
}
