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
package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Import rules that keep the layers apart. They complement the module dependency matrix of the build, which only sees
 * project dependencies, with the external libraries and JDK packages each layer may use.
 *
 * - Compose Multiplatform for Desktop is the UI toolkit. It stays in the UI and app modules, so every other module is
 *   UI-agnostic. JavaFX is not used at all.
 * - Core and notation are the reusable layers. They see no layer above them and no infrastructure library.
 * - The IDE can run the runtime in a Docker container that it owns. Only the Docker engine adapter of runtime-host
 *   talks to Docker, so the host behind it is tested against a fake engine and Docker types never reach its API.
 * - The client of the runtime reaches the network through its own transport seams. Only their Ktor adapter package
 *   uses Ktor, in its code and in its tests. Everything else of the runtime, test fixtures included, works against the
 *   seams. The bridges keep their own use of Ktor.
 * - Bridges are headless first. Of the runtime they use only its wire protocol. Only the MQTT bridge uses the HiveMQ
 *   client.
 *
 * The rules apply to the main, test and test-fixture sources of every module, identified by package. Each rule checks
 * the import directives. It also checks the fully qualified references in the code ([SourceReferences]), because JDK
 * packages such as java.awt compile without any dependency and so need no import.
 */
class LayerImportRulesTest {

    private companion object {
        const val IDE = "io.karpfen.ide"
        const val CORE = "$IDE.core"
        const val NOTATION = "$IDE.notation"
        const val RUNTIME = "$IDE.runtime"
        const val RUNTIME_PROTOCOL = "$IDE.runtime.protocol"
        const val RUNTIME_DOCKER_ENGINE = "$IDE.runtime.host.docker.engine"
        const val RUNTIME_KTOR_ADAPTER = "$IDE.runtime.protocol.transport.ktor"
        const val BRIDGES = "$IDE.bridges"
        const val BRIDGES_MQTT = "$IDE.bridges.mqtt"
        const val UI = "$IDE.ui"
        const val APP = "$IDE.app"
        const val TOOLS = "$IDE.tools"

        /** The root packages of Compose Multiplatform. */
        val COMPOSE = listOf("androidx.compose", "org.jetbrains.compose")

        /** Compose and the other desktop UI toolkits of the JVM. */
        val DESKTOP_UI_TOOLKITS = COMPOSE + listOf("javafx", "java.awt", "javax.swing")

        /** The root package of docker-java, the Docker client of runtime-host. */
        const val DOCKER = "com.github.dockerjava"

        /** The root package of the HiveMQ MQTT client. */
        const val HIVEMQ = "com.hivemq"

        /** The root package of Ktor, the HTTP and WebSocket library. */
        const val KTOR = "io.ktor"

        /** Infrastructure libraries that the core and notation layers must never see. */
        val INFRASTRUCTURE = listOf(KTOR, DOCKER, HIVEMQ) + DESKTOP_UI_TOOLKITS
    }

    @Test
    fun `core is independent of every other layer and of UI and infrastructure libraries`() {
        assertNoUsages(
            files = KarpfenModules.filesInPackage(CORE),
            forbidden = listOf(NOTATION, RUNTIME, BRIDGES, UI, APP, TOOLS) + INFRASTRUCTURE,
            rule = "$CORE.. must not import notation, runtime, bridges, ui, app, tools, " +
                "Compose, Ktor, Docker, HiveMQ, JavaFX, AWT or Swing",
        )
    }

    @Test
    fun `notation may use core but no other layer and no UI or infrastructure library`() {
        assertNoUsages(
            files = KarpfenModules.filesInPackage(NOTATION),
            forbidden = listOf(RUNTIME, BRIDGES, UI, APP, TOOLS) + INFRASTRUCTURE,
            rule = "$NOTATION.. must not import runtime, bridges, ui, app, tools, Compose, Ktor, Docker, HiveMQ, JavaFX, AWT or Swing",
        )
    }

    @Test
    fun `runtime is independent of ui, notation, bridges, app, tools and UI toolkits`() {
        assertNoUsages(
            files = KarpfenModules.filesInPackage(RUNTIME),
            forbidden = listOf(UI, NOTATION, BRIDGES, APP, TOOLS) + DESKTOP_UI_TOOLKITS,
            rule = "$RUNTIME.. must not import ui, notation, bridges, app, tools, Compose, JavaFX, AWT or Swing",
        )
    }

    @Test
    fun `bridges use only the runtime protocol and no ui, notation, app or tools`() {
        val forbiddenRuntime = listOf(RUNTIME)
        val forbiddenLayers = listOf(UI, NOTATION, APP, TOOLS)
        assertNoUsages(
            files = KarpfenModules.filesInPackage(BRIDGES),
            rule = "$BRIDGES.. may import $RUNTIME_PROTOCOL.. but no other runtime package, and not ui, notation, app or tools",
        ) { name ->
            (name.isWithinAny(forbiddenRuntime) && !name.isWithin(RUNTIME_PROTOCOL)) || name.isWithinAny(forbiddenLayers)
        }
    }

    @Test
    fun `only the Docker engine adapter of runtime-host may use the Docker client`() {
        val adapter = KarpfenModules.filesInPackage(RUNTIME_DOCKER_ENGINE)
        assertTrue(
            adapter.any { file -> file.sourceSetName == "main" && file.imports.any { it.name.isWithin(DOCKER) } },
            "nothing in $RUNTIME_DOCKER_ENGINE uses $DOCKER; is the rule still aimed at the right package?",
        )
        assertNoUsages(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(RUNTIME_DOCKER_ENGINE) },
            forbidden = listOf(DOCKER),
            rule = "Only $RUNTIME_DOCKER_ENGINE.. may import $DOCKER..",
        )
    }

    @Test
    fun `within the runtime only the Ktor adapter of the protocol may use Ktor`() {
        val runtimeFiles = KarpfenModules.filesInPackage(RUNTIME)
        assertTrue(runtimeFiles.any { it.sourceSetName == "testFixtures" }, "the rule must see the test fixtures of the runtime modules")
        assertNoUsages(
            files = runtimeFiles.filterNot { it.packageName.isWithin(RUNTIME_KTOR_ADAPTER) },
            forbidden = listOf(KTOR),
            rule = "Within $RUNTIME.. only $RUNTIME_KTOR_ADAPTER.. may import $KTOR..",
        )
    }

    @Test
    fun `only bridge-mqtt may use the HiveMQ client`() {
        assertNoUsages(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(BRIDGES_MQTT) },
            forbidden = listOf(HIVEMQ),
            rule = "Only $BRIDGES_MQTT.. may import $HIVEMQ..",
        )
    }

    @Test
    fun `Compose, AWT and Swing are confined to ui and app`() {
        assertNoUsages(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithinAny(listOf(UI, APP)) },
            forbidden = DESKTOP_UI_TOOLKITS - "javafx",
            rule = "Desktop UI toolkits (Compose, AWT, Swing) may only be used by $UI.. and $APP.. " +
                "(every other module is UI-agnostic, and bridges are headless)",
        )
    }

    @Test
    fun `nothing imports JavaFX`() {
        assertNoUsages(
            files = KarpfenModules.moduleFiles,
            forbidden = listOf("javafx"),
            rule = "JavaFX is not used anywhere (the UI toolkit is Compose Multiplatform for Desktop)",
        )
    }

    /**
     * Fails if an import or a fully qualified reference in [files] lies within one of the [forbidden] packages. The
     * function is inline, like the overload it delegates to, because Konsist derives the test name from the call stack.
     * Inlined into the test method, it lets Konsist report and suppress a failure under the name of that method.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun assertNoUsages(files: List<KoFileDeclaration>, forbidden: List<String>, rule: String) {
        assertNoUsages(files, rule) { name -> name.isWithinAny(forbidden) }
    }

    /**
     * Fails if an import or a fully qualified reference in [files] is [isForbidden]. The function is inline because
     * Konsist derives the test name from the call stack. Inlined into the test method, it lets Konsist report and
     * suppress a failure under the name of that method.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun assertNoUsages(files: List<KoFileDeclaration>, rule: String, noinline isForbidden: (String) -> Boolean) {
        assertTrue(files.isNotEmpty(), "Rule '$rule' matched no files; is the Konsist scope broken?")
        files.flatMap { it.imports }.assertFalse(additionalMessage = rule) { import -> isForbidden(import.name) }

        val qualified: Map<KoFileDeclaration, List<QualifiedReference>> = files
            .associateWith { file -> file.qualifiedReferences.filter { isForbidden(it.name) } }
            .filterValues { it.isNotEmpty() }
        files.assertFalse(
            additionalMessage = "$rule (fully qualified references count as imports: " +
                qualified.entries.joinToString { (file, references) -> "${file.projectPath}: ${references.joinToString()}" } + ")",
        ) { file -> file in qualified }
    }
}
