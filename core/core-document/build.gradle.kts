plugins {
    id("karpfen.kotlin-jvm")
    // Shared fixtures (spec §10): example files, mutations, synthetic documents; consumed by the tests of this module
    // and, through testFixtures(project(":core:core-document")), by the tests of downstream modules.
    `java-test-fixtures`
}

dependencies {
    // Public API: TextSnapshot/EditableText (core-text) and the dsl-tools semantic model types (meta.*, instance.*,
    // states.*) in DslModel, DslConverter and, downstream, the project analysis (D2).
    api(project(":core:core-text"))
    api(libs.karpfen.dsl.tools) {
        // dsl-tools leaks the ANTLR *tool* (antlr4 + ST4 + ICU4J) into its API through the Gradle antlr
        // plugin; the generated parsers only need the runtime, which is pinned below.
        exclude(group = "org.antlr", module = "antlr4")
    }
    // implementation, not api: only the internal adapter package io.karpfen.ide.core.document.antlr may use ANTLR or
    // the generated parsers (architecture-tests, spec §0), so no public signature exposes ANTLR types and downstream
    // modules compile without it. It stays on their runtime classpath, which dsl-tools' parsers need.
    implementation(libs.antlr4.runtime)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}

// Composite mode (docs/adr/0010-dsl-tools-resolution-modes.md): the build script of karpfen-dsl-tools is not
// configuration-cache compatible yet (its fixKmodelPackages/fixKstatesPackages tasks read `projectDir` at
// execution time). Marking the compilation that consumes it as incompatible lets Gradle degrade gracefully
// (that run executes without storing a cache entry) instead of failing. Artifact mode keeps the configuration
// cache. Remove once dsl-tools stops rewriting its generated sources (architecture proposal §5.1, item 6).
if (gradle.includedBuilds.any { it.name == "karpfen-dsl-tools" }) {
    tasks.named("compileKotlin") {
        notCompatibleWithConfigurationCache("karpfen-dsl-tools (composite build) is not configuration-cache compatible")
    }
}
