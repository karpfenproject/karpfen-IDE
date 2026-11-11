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
/**
 * The module builds as Kotlin/JVM with kotlinx.serialization, because `EnvironmentDescriptor` is serializable. It also
 * publishes shared test fixtures: the example files, mutations and synthetic documents. The tests of this module use
 * them, and the tests of downstream modules reach them through `testFixtures(project(":core:core-document"))`.
 */
plugins {
    id("karpfen.kotlin-serialization")
    `java-test-fixtures`
}

/**
 * The public API exposes three groups of types. The first is `TextSnapshot` and `EditableText` of core-text. The second
 * is the semantic model types of dsl-tools, from the packages `io.karpfen.dsl.meta`, `io.karpfen.dsl.instance` and
 * `io.karpfen.dsl.states`. They appear in `DslModel` and `DslReader` and, downstream, in the project analysis. The
 * third is the serializable `EnvironmentDescriptor`. Its JSON shape is the format of the environment descriptors that
 * the IDE reads and that its deployment works from. The runtime never reads these descriptors.
 *
 * karpfen-dsl-tools is the language core of the IDE. The IDE adds only editor concerns and has no parser of its own
 * for the modeling languages.
 *
 * core-document declares the ANTLR runtime itself, because its adapter package `io.karpfen.ide.core.document.antlr`
 * uses it directly. It declares no version, though. dsl-tools publishes the runtime as part of its own API, because
 * its generated lexers and parsers are public. That version is the one the parsers were generated for, so the IDE
 * always runs them with the runtime they expect. `AntlrRuntimeVersionTest` checks this. The runtime also reaches the
 * compile classpath of downstream modules this way. The architecture tests keep every use of ANTLR and of the
 * generated parsers inside the adapter package, and no public signature of the IDE exposes an ANTLR type.
 */
dependencies {
    api(project(":core:core-text"))
    api(libs.karpfen.dsl.tools)
    api(libs.kotlinx.serialization.json)

    implementation(libs.antlr4.runtime)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.slf4j.api)
}
