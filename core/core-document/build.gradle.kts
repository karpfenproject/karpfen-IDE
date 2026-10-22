/**
 * Kotlin/JVM with kotlinx.serialization, because `EnvironmentDescriptor` is serializable, and shared test fixtures: the
 * example files, mutations and synthetic documents, which the tests of this module use and the tests of downstream
 * modules reach through `testFixtures(project(":core:core-document"))`.
 */
plugins {
    id("karpfen.kotlin-serialization")
    `java-test-fixtures`
}

/**
 * The public API holds `TextSnapshot` and `EditableText` of core-text, the dsl-tools semantic model types (`meta.*`,
 * `instance.*`, `states.*`) in `DslModel`, `DslConverter` and, downstream, the project analysis, and the serializable
 * `EnvironmentDescriptor`, whose JSON shape is the contract with the runtime. karpfen-dsl-tools is the language core of
 * the IDE; the IDE adds only editor concerns and has no parser of its own for the modeling languages. dsl-tools leaks
 * the ANTLR tool (antlr4 with ST4 and ICU4J) into its API through the Gradle antlr plugin, so the tool is excluded: the
 * generated parsers need only the ANTLR runtime.
 *
 * The ANTLR runtime is an implementation dependency, not an API one: only the internal adapter package
 * `io.karpfen.ide.core.document.antlr` may use ANTLR or the generated parsers (the architecture tests enforce it), so no
 * public signature exposes ANTLR types and downstream modules compile without it. It stays on their runtime classpath,
 * which the dsl-tools parsers need.
 */
dependencies {
    api(project(":core:core-text"))
    api(libs.karpfen.dsl.tools) {
        exclude(group = "org.antlr", module = "antlr4")
    }
    api(libs.kotlinx.serialization.json)

    implementation(libs.antlr4.runtime)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * In composite mode, where the settings include a local checkout of karpfen-dsl-tools as a build, its build script is
 * not configuration-cache compatible yet: its `fixKmodelPackages` and `fixKstatesPackages` tasks read `projectDir` at
 * execution time. Marking the compilation that consumes it as incompatible lets Gradle degrade gracefully (that run
 * executes without storing a cache entry) instead of failing. Artifact mode keeps the configuration cache. dsl-tools
 * tasks invoked directly, such as `./gradlew :karpfen-dsl-tools:jar`, need `--no-configuration-cache`. Remove this once
 * dsl-tools generates its ANTLR sources at build time instead of rewriting committed ones.
 */
if (gradle.includedBuilds.any { it.name == "karpfen-dsl-tools" }) {
    tasks.named("compileKotlin") {
        notCompatibleWithConfigurationCache("karpfen-dsl-tools (composite build) is not configuration-cache compatible")
    }
}
