pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Keep in sync with [versions] foojay-resolver in gradle/libs.versions.toml
    // (version catalogs are not available in the settings plugins block).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
        // Only karpfen artifacts (karpfen-dsl-tools in artifact mode) may come from ~/.m2.
        mavenLocal {
            content { includeGroup("io.karpfen") }
        }
    }
    // The version catalog is picked up from gradle/libs.versions.toml (default location) as `libs`.
}

rootProject.name = "karpfen-ide"

// ---------------------------------------------------------------------------------------------
// karpfen-dsl-tools resolution (docs/adr/0010-dsl-tools-resolution-modes.md)
//
// dslToolsPath is read from, in order:
//   1. the Gradle property      -PdslToolsPath=<path>
//   2. local.properties         dslToolsPath=<path>   (git-ignored, per machine)
//   3. the default              ../karpfen-dsl-tools  (relative to this directory)
// A relative path is resolved against this directory. A blank value disables the composite build.
// If the directory holds a Gradle build (settings.gradle or settings.gradle.kts), it is included as
// a composite build and substitutes io.karpfen:karpfen-dsl-tools. Otherwise the artifact declared in
// the version catalog is resolved from the repositories above (mavenLocal locally, a published
// repository later).
// ---------------------------------------------------------------------------------------------
run {
    val settingsLogger = Logging.getLogger("karpfen-settings")
    val rootDirectory = layout.rootDirectory

    val fromGradleProperty: String? = providers.gradleProperty("dslToolsPath").orNull
    val fromLocalProperties: String? = providers.fileContents(rootDirectory.file("local.properties")).asText.orNull
        ?.let { text -> java.util.Properties().apply { load(text.reader()) }.getProperty("dslToolsPath") }
    val explicitlyConfigured = fromGradleProperty != null || fromLocalProperties != null
    val dslToolsPath: String = (fromGradleProperty ?: fromLocalProperties ?: "../karpfen-dsl-tools").trim()

    val dslToolsBuildDir: File? = dslToolsPath
        .takeIf { it.isNotEmpty() }
        ?.let { rootDirectory.asFile.resolve(it).normalize() }
        ?.takeIf { dir -> dir.resolve("settings.gradle").isFile || dir.resolve("settings.gradle.kts").isFile }

    if (dslToolsBuildDir != null) {
        settingsLogger.lifecycle("karpfen-dsl-tools: composite build from $dslToolsBuildDir")
        includeBuild(dslToolsBuildDir) {
            // Fixed name regardless of the checkout directory name (core/core-document checks for it).
            name = "karpfen-dsl-tools"
            dependencySubstitution {
                substitute(module("io.karpfen:karpfen-dsl-tools")).using(project(":"))
            }
        }
    } else {
        if (explicitlyConfigured && dslToolsPath.isNotEmpty()) {
            settingsLogger.warn("karpfen-dsl-tools: dslToolsPath '$dslToolsPath' is not a Gradle build; falling back to the artifact")
        }
        val catalog = providers.fileContents(rootDirectory.file("gradle/libs.versions.toml")).asText.get()
        val version = Regex("""(?m)^\s*karpfen-dsl-tools\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1) ?: "?"
        settingsLogger.lifecycle("karpfen-dsl-tools: resolving io.karpfen:karpfen-dsl-tools:$version from repositories")
    }
}

// ---------------------------------------------------------------------------------------------
// Modules: the project path mirrors the directory path (core/core-text -> :core:core-text).
// Later (not created yet): runtime/runtime-embedded, tools/lsp-server.
// ---------------------------------------------------------------------------------------------
listOf(
    "core/core-text",
    "core/core-document",
    "core/core-workspace",
    "core/core-lang",
    "runtime/runtime-protocol",
    "runtime/runtime-session",
    "runtime/runtime-host",
    "notation/notation-diagram",
    "notation/notation-sync",
    "bridges/bridge-api",
    "bridges/bridge-mapping",
    "bridges/bridge-mqtt",
    "bridges/bridge-ros",
    "bridges/bridge-runner",
    "ui/ui-foundation",
    "ui/ui-editor",
    "ui/ui-diagram",
    "ui/ui-workbench",
    "app/ide",
    "tools/cli",
    "architecture-tests",
).forEach { dir -> include(":" + dir.replace('/', ':')) }
