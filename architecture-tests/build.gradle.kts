plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

/**
 * The architecture tests read files of the whole repository from disk: Konsist reads the Kotlin sources of all
 * modules, `DocumentationStyleTest` every Kotlin source file and Gradle script, build-logic's included, and
 * `PackageLayoutTest` looks for Java sources. All of these files are declared as inputs, so the architecture tests
 * rerun, and are never served stale from the build cache, when any of them changes. Build output and hidden
 * directories are left out, as the tests skip them.
 */
tasks.test {
    inputs.files(
        fileTree(layout.settingsDirectory) {
            include("**/src/**/*.kt", "**/src/**/*.kts", "**/src/**/*.java", "**/*.gradle.kts")
            exclude("**/build/**", "**/.*/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleSources")
}
