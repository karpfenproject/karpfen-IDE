pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

/**
 * The foojay toolchain resolver, which lets Gradle download a missing JDK 21 for the toolchain. Its version must be kept
 * in sync by hand with `foojay-resolver` in the `[versions]` table of `gradle/libs.versions.toml`, because the version
 * catalog is not available in the `plugins` block of the settings.
 */
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

/**
 * The repositories of every module; a module must not declare its own. Only karpfen artifacts, that is
 * karpfen-dsl-tools in artifact mode, may come from the local Maven repository in `~/.m2`. The version catalog `libs`
 * is picked up from `gradle/libs.versions.toml`, its default location.
 */
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
        mavenLocal {
            content { includeGroup("io.karpfen") }
        }
    }
}

rootProject.name = "karpfen-ide"

/**
 * Resolves karpfen-dsl-tools, the language core of the IDE, in one of two modes.
 *
 * - Composite build: if `dslToolsPath` names a directory that holds a Gradle build (`settings.gradle` or
 *   `settings.gradle.kts`), that build is included and substitutes the module `io.karpfen:karpfen-dsl-tools`, so a
 *   change that spans both repositories is a single local iteration. The included build is always named
 *   `karpfen-dsl-tools`, whatever its checkout directory is called, because the build script of core-document looks it
 *   up by that name.
 * - Artifact: otherwise the version declared in the version catalog is resolved from the repositories above, the local
 *   Maven repository for now and a published repository later. An explicitly configured path that holds no Gradle
 *   build falls back to the artifact with a warning.
 *
 * `dslToolsPath` is read from, in this order, the Gradle property `-PdslToolsPath=<path>`, the git-ignored per-machine
 * `local.properties` (`dslToolsPath=<path>`) and the default `../karpfen-dsl-tools`. A relative path is resolved
 * against the repository root; a blank value disables the composite build. `local.properties` is read with
 * `java.util.Properties`, where a backslash is an escape character, so Windows paths are written with forward slashes
 * (`C:/work/karpfen-dsl-tools`) or with doubled backslashes.
 *
 * The settings log the active mode, though not on a configuration-cache hit, where `--no-configuration-cache` or
 * `dependencyInsight` shows it. CI clones dsl-tools at a pinned commit into the default path and builds in composite
 * mode until dsl-tools publishes versioned artifacts.
 */
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

/**
 * The modules of the build. A module's project path mirrors its directory: `core/core-text` becomes `:core:core-text`.
 * Planned modules that do not exist yet, the embedded runtime `runtime/runtime-embedded` and the language server
 * `tools/lsp-server`, are added here when they are created, together with a rule in the module dependency matrix and
 * an entry in the module table of the architecture tests.
 */
val modulePaths: List<String> = listOf(
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
).map { dir -> ":" + dir.replace('/', ':') }

modulePaths.forEach { include(it) }

/**
 * Fails for every module that does not apply `karpfen.kotlin-jvm`, directly or through one of the other karpfen.*
 * convention plugins, which all build on it. Without it a module would have no `verifyModuleDependencies` task and
 * could bypass the module dependency matrix unnoticed. The module paths are copied into a local value first, because
 * the isolated `afterProject` action must not capture the settings script object.
 */
run {
    val conventionModules: Set<String> = modulePaths.toSet()
    gradle.lifecycle.afterProject {
        if (path in conventionModules && !pluginManager.hasPlugin("karpfen.kotlin-jvm")) {
            throw GradleException(
                "$path does not apply karpfen.kotlin-jvm (directly or through karpfen.compose, karpfen.application or " +
                    "karpfen.kotlin-serialization); every module needs it for the module dependency rules " +
                    "(ModuleDependencyRules in build-logic)",
            )
        }
    }
}
