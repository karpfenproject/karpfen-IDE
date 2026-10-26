plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

/**
 * The architecture tests read files of the whole repository from disk: Konsist reads the Kotlin sources of all
 * modules, `DocumentationStyleTest` every Kotlin source file and Gradle script, build-logic's included, and the
 * markdown documents, CI workflows, XML resources and version catalog, and `PackageLayoutTest` looks for Java sources.
 * All of these files are declared as inputs, so the architecture tests rerun, and are never served stale from the
 * build cache, when any of them changes. Build output, the `build` directories of the root, of build-logic and of the
 * modules, hidden directories other than `.github`, and the checkouts of other repositories in git submodules (read
 * from `.gitmodules`) are left out, as the tests skip them; a source package named `build` is an input like any other.
 */
val submoduleDirectories: List<String> = layout.settingsDirectory.file(".gitmodules").asFile
    .takeIf { it.isFile }?.readLines().orEmpty()
    .mapNotNull { line -> Regex("""^\s*path\s*=\s*(.+?)\s*$""").find(line)?.groupValues?.get(1)?.trimEnd('/') }

tasks.test {
    inputs.files(
        fileTree(layout.settingsDirectory) {
            include(
                "**/src/**/*.kt", "**/src/**/*.kts", "**/src/**/*.java", "**/*.gradle.kts", "**/*.md",
                "**/src/**/resources/**/*.xml", "gradle/*.toml", ".gitmodules",
            )
            exclude("build/**", "*/build/**", "*/*/build/**", "**/.*/**")
            submoduleDirectories.forEach { exclude("$it/**") }
        },
        fileTree(layout.settingsDirectory.dir(".github")),
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleSources")
}
