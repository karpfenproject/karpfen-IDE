plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // DocumentSnapshot exposes the text (core-text) and the parse result (dsl-tools model types, D2).
    api(project(":core:core-text"))
    api(libs.karpfen.dsl.tools) {
        // dsl-tools leaks the ANTLR *tool* (antlr4 + ST4 + ICU4J) into its API through the Gradle antlr
        // plugin; the generated parsers only need the runtime, which is pinned below.
        exclude(group = "org.antlr", module = "antlr4")
    }
    // api, not implementation: the excluded antlr4 was what carried the runtime onto consumers' compile classpaths,
    // and dsl-tools' public lexers/parsers (kmeta.KmetaLexer, ...) extend org.antlr.v4.runtime types. Downstream
    // modules (e.g. tokenizer-based highlighting in core-lang/ui-editor, D2) must see those supertypes.
    api(libs.antlr4.runtime)
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
