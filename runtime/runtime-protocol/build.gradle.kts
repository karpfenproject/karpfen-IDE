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
import io.karpfen.ide.buildlogic.GitSubmodules

/**
 * The typed client of the karpfen runtime: its HTTP routes and its WebSocket as data, the runtime's JSON, the
 * environment lifecycle and the authenticated push channel. Its tests also run against a real runtime through the live
 * task of `karpfen.live-runtime-test`, and its test fixtures hold what every test against a runtime shares: the port of
 * the runtime's JSON writers, the synthesized models and the scenario types with their waiting helpers.
 */
plugins {
    id("karpfen.live-runtime-test")
    `java-test-fixtures`
}

/**
 * The module depends on no other module of the IDE, so the bridges that build on it stay free of the core. Coroutines
 * are `api`, because flows, state flows, channels and deferred values are part of the public API; Ktor is
 * `implementation` and confined to the adapter package `transport.ktor`, so no Ktor type appears in the API. The
 * runtime's JSON is read and quoted by the module itself (strict JSON plus the runtime's bare non-finite numbers, with
 * the field order kept), so neither kotlinx.serialization nor Ktor's content negotiation is needed. Tests use Ktor's
 * mock engine and virtual time. The test fixtures need JUnit for the annotation of the live tests and for the failures
 * of the waiting helpers; the JUnit artifacts have no version of their own in the catalog, so the fixtures declare the
 * BOM platform themselves. org.json, the runtime's own JSON library in the version the runtime pins, is used only by
 * the fixtures, so that they write and parse JSON exactly as the runtime does.
 */
dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.slf4j.api)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesImplementation(libs.org.json)
}

/**
 * The checkout of the karpfen-runtime submodule: the `.gitmodules` entry whose last segment is `karpfen-runtime`, read
 * with build-logic's `GitSubmodules`, the one reader of that file. Null when the repository has no such submodule.
 */
val runtimeCheckout: File? =
    GitSubmodules.parse(providers.fileContents(layout.settingsDirectory.file(".gitmodules")).asText.orNull.orEmpty())
        .firstOrNull { it.substringAfterLast('/') == "karpfen-runtime" }
        ?.let { layout.settingsDirectory.dir(it).asFile }

/**
 * The tests compare the client's catalogue with the runtime's own sources, so they get the absolute path of the
 * runtime checkout as the system property `karpfen.runtime.checkout`, and the runtime's server and engine sources are
 * inputs of the task by their relative paths: moving the submodule reruns the tests, wherever the repository lies.
 */
tasks.test {
    runtimeCheckout?.let { checkout ->
        systemProperty("karpfen.runtime.checkout", checkout.absolutePath)
        inputs.files(
            fileTree(checkout.resolve("src/main/kotlin")),
            fileTree(checkout.resolve("execution-engine/src/main/kotlin")),
        ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("runtimeSources")
    }
}

/**
 * `RuntimeContainerScriptTest` runs `runtime/live-runtime.sh`, the script that starts a runtime in Docker for the live
 * tests, with Docker, git and curl replaced by shell functions, so the script reaches the tests as the system property
 * `karpfen.runtime.script` and is an input of the task by its relative path: changing it reruns the tests.
 */
tasks.test {
    val script = layout.settingsDirectory.file("runtime/live-runtime.sh")
    systemProperty("karpfen.runtime.script", script.asFile.absolutePath)
    inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("containerScript")
}
