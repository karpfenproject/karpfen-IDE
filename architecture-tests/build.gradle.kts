plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

/**
 * Konsist reads the Kotlin sources of all modules from disk, and `PackageLayoutTest` rejects Java sources. Both kinds
 * of source files are declared as inputs, so the architecture tests rerun, and are never served stale from the build
 * cache, when any module changes.
 */
tasks.test {
    inputs.files(
        fileTree(layout.settingsDirectory) {
            include("**/src/**/*.kt", "**/src/**/*.java")
            exclude("build-logic/**", "**/build/**", ".*/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleSources")
}
