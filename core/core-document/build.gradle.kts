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
 * The public API holds `TextSnapshot` and `EditableText` of core-text, the dsl-tools semantic model types (the packages
 * `io.karpfen.dsl.meta`, `io.karpfen.dsl.instance` and `io.karpfen.dsl.states`) in `DslModel`, `DslConverter` and,
 * downstream, the project analysis, and the serializable `EnvironmentDescriptor`, whose JSON shape is the contract with
 * the runtime. karpfen-dsl-tools is the language core of the IDE; the IDE adds only editor concerns and has no parser of
 * its own for the modeling languages.
 *
 * core-document declares the ANTLR runtime itself, because its adapter package `io.karpfen.ide.core.document.antlr`
 * uses it directly, and pins it to the version that generates the dsl-tools parsers. dsl-tools publishes the runtime as
 * part of its own API, since its generated lexers and parsers are public, so it also reaches the compile classpath of
 * downstream modules; the architecture tests keep every use of ANTLR and of the generated parsers inside that adapter
 * package, and no public signature of the IDE exposes an ANTLR type.
 */
dependencies {
    api(project(":core:core-text"))
    api(libs.karpfen.dsl.tools)
    api(libs.kotlinx.serialization.json)

    implementation(libs.antlr4.runtime)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
}
