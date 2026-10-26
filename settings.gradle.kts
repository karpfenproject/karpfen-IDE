/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * The repositories of every module; a module must not declare its own. karpfen-dsl-tools comes from its submodule, or
 * in artifact mode from GitHub Packages, which the resolution below adds; the build never reads from or writes to the
 * local Maven repository in `~/.m2`. The version catalog `libs` is picked up from `gradle/libs.versions.toml`, its
 * default location.
 */
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "karpfen-ide"

/** The directory of the karpfen-dsl-tools git submodule, relative to the repository root. */
val DEFAULT_DSL_TOOLS_PATH = "karpfen-dsl-tools"

/**
 * The repository to which karpfen-dsl-tools publishes its versions, GitHub Packages of its GitHub repository. Reading
 * from it needs credentials, the Gradle properties `GitHubPackagesUsername` and `GitHubPackagesPassword`, the names
 * dsl-tools publishes with.
 */
val DSL_TOOLS_PACKAGES = "https://maven.pkg.github.com/karpfenproject/karpfen-dsl-tools"

/**
 * The source directory below which dsl-tools 2.0 and newer keep their Kotlin packages, `io.karpfen.dsl`; version 1.0
 * kept them in root packages such as `dsl.textual` and `meta`, which the IDE no longer compiles against.
 */
val DSL_TOOLS_TWO_SOURCES = "src/main/kotlin/io/karpfen/dsl"

/**
 * Fails the build with a message that names [dir] and the way out when the dsl-tools checkout there is older than
 * version 2.0 ([DSL_TOOLS_TWO_SOURCES]), such as a submodule that `git submodule update` has not moved yet or a
 * configured checkout of version 1.0: compiling against it would otherwise end in many unresolved references and name
 * neither the version nor the checkout.
 */
fun requireDslToolsTwo(dir: File, explicitlyConfigured: Boolean) {
    if (dir.resolve(DSL_TOOLS_TWO_SOURCES).isDirectory) return
    val remedy = if (explicitlyConfigured) {
        "point dslToolsPath at a checkout of dsl-tools 2.0 or newer, or remove it to build with the submodule"
    } else {
        "run 'git submodule update --init' to check out the commit this repository records"
    }
    throw GradleException(
        "karpfen-dsl-tools at $dir is older than version 2.0 (it has no $DSL_TOOLS_TWO_SOURCES); the IDE needs 2.0 or " +
            "newer: $remedy",
    )
}

/**
 * Resolves karpfen-dsl-tools, the language core of the IDE, in one of two modes.
 *
 * - Composite build: karpfen-dsl-tools is a git submodule of this repository in the directory `karpfen-dsl-tools`, and
 *   that checkout is the default. If `dslToolsPath` names a directory that holds a Gradle build (`settings.gradle` or
 *   `settings.gradle.kts`), that build is included and substitutes the module `io.karpfen:karpfen-dsl-tools`, so a
 *   change that spans both repositories is a single local iteration. The included build is always named
 *   `karpfen-dsl-tools`, whatever its directory is called, so its tasks keep one path, such as `:karpfen-dsl-tools:test`.
 *   The checkout must hold dsl-tools 2.0 or newer; an older one fails the build at once ([requireDslToolsTwo]).
 * - Artifact: a blank `dslToolsPath` switches the composite build off, and the version declared in the version catalog
 *   is resolved from GitHub Packages ([DSL_TOOLS_PACKAGES]), where dsl-tools publishes, with the credentials named
 *   there; without them the build stops at once and names the missing properties. It resolves only once dsl-tools has
 *   published the version that the catalog names.
 *
 * Any other path stops the build with a message that names the way out: an uninitialized submodule, the default, with
 * the command that checks it out, and a configured path that holds no Gradle build with the setting to change. Neither
 * falls back to the artifact, which would fail later and less clearly in dependency resolution.
 *
 * `dslToolsPath` is read from, in this order, the Gradle property `-PdslToolsPath=<path>`, the git-ignored per-machine
 * `local.properties` (`dslToolsPath=<path>`) and the default, the submodule directory `karpfen-dsl-tools`. A relative
 * path is resolved against the repository root. `local.properties` is read with `java.util.Properties`, where a
 * backslash is an escape character, so Windows paths are written with forward slashes (`C:/work/karpfen-dsl-tools`) or
 * with doubled backslashes.
 *
 * The settings log the active mode (`karpfen-dsl-tools: composite build from <path>` or `karpfen-dsl-tools: resolving
 * <module> from GitHub Packages`), though not on a configuration-cache hit, where `--no-configuration-cache` or
 * `./gradlew :core:core-document:dependencyInsight --dependency karpfen-dsl-tools --configuration compileClasspath`
 * shows it. CI checks the submodule out together with this repository and builds in composite mode.
 */
run {
    val settingsLogger = Logging.getLogger("karpfen-settings")
    val rootDirectory = layout.rootDirectory

    val fromGradleProperty: String? = providers.gradleProperty("dslToolsPath").orNull
    val fromLocalProperties: String? = providers.fileContents(rootDirectory.file("local.properties")).asText.orNull
        ?.let { text -> java.util.Properties().apply { load(text.reader()) }.getProperty("dslToolsPath") }
    val explicitlyConfigured = fromGradleProperty != null || fromLocalProperties != null
    val dslToolsPath: String = (fromGradleProperty ?: fromLocalProperties ?: DEFAULT_DSL_TOOLS_PATH).trim()

    val dslToolsBuildDir: File? = dslToolsPath
        .takeIf { it.isNotEmpty() }
        ?.let { rootDirectory.asFile.resolve(it).normalize() }
        ?.takeIf { dir -> dir.resolve("settings.gradle").isFile || dir.resolve("settings.gradle.kts").isFile }

    if (dslToolsBuildDir != null) {
        requireDslToolsTwo(dslToolsBuildDir, explicitlyConfigured)
        settingsLogger.lifecycle("karpfen-dsl-tools: composite build from $dslToolsBuildDir")
        includeBuild(dslToolsBuildDir) {
            name = "karpfen-dsl-tools"
            dependencySubstitution {
                substitute(module("io.karpfen:karpfen-dsl-tools")).using(project(":"))
            }
        }
    } else if (dslToolsPath.isEmpty()) {
        dependencyResolutionManagement {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri(DSL_TOOLS_PACKAGES)
                    credentials(PasswordCredentials::class)
                    content { includeGroup("io.karpfen") }
                }
            }
        }
        val catalog = providers.fileContents(rootDirectory.file("gradle/libs.versions.toml")).asText.get()
        val version = Regex("""(?m)^\s*karpfen-dsl-tools\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1) ?: "?"
        settingsLogger.lifecycle("karpfen-dsl-tools: resolving io.karpfen:karpfen-dsl-tools:$version from GitHub Packages")
    } else if (explicitlyConfigured) {
        throw GradleException(
            "karpfen-dsl-tools: dslToolsPath '$dslToolsPath' holds no Gradle build; point it at a checkout of dsl-tools " +
                "2.0 or newer, or remove it to build with the submodule",
        )
    } else {
        throw GradleException(
            "karpfen-dsl-tools: the submodule '$DEFAULT_DSL_TOOLS_PATH' is not initialized; run " +
                "'git submodule update --init' to check out the commit this repository records",
        )
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
