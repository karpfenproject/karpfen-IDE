package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Import rules that keep the layers apart (D4, D5, D6, D7 in docs/architecture-proposal.md).
 * They apply to main and test sources of every module, identified by package. Each rule checks the import directives
 * and, because JDK packages such as java.awt compile without any dependency, also fully qualified references in the
 * code (see [SourceReferences]).
 */
class LayerImportRulesTest {

    private companion object {
        const val IDE = "io.karpfen.ide"
        const val CORE = "$IDE.core"
        const val NOTATION = "$IDE.notation"
        const val RUNTIME = "$IDE.runtime"
        const val RUNTIME_PROTOCOL = "$IDE.runtime.protocol"
        const val RUNTIME_HOST = "$IDE.runtime.host"
        const val BRIDGES = "$IDE.bridges"
        const val BRIDGES_MQTT = "$IDE.bridges.mqtt"
        const val UI = "$IDE.ui"
        const val APP = "$IDE.app"
        const val TOOLS = "$IDE.tools"

        val COMPOSE = listOf("androidx.compose", "org.jetbrains.compose")
        val DESKTOP_UI_TOOLKITS = COMPOSE + listOf("javafx", "java.awt", "javax.swing")
        const val DOCKER = "com.github.dockerjava"
        const val HIVEMQ = "com.hivemq"
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
    fun `only runtime-host may use the Docker client`() {
        assertNoUsages(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(RUNTIME_HOST) },
            forbidden = listOf(DOCKER),
            rule = "Only $RUNTIME_HOST.. may import $DOCKER..",
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
                "(D4: every other module is UI-agnostic; D6: bridges are headless)",
        )
    }

    @Test
    fun `nothing imports JavaFX`() {
        assertNoUsages(
            files = KarpfenModules.moduleFiles,
            forbidden = listOf("javafx"),
            rule = "JavaFX is not used anywhere (D4: Compose Multiplatform for Desktop)",
        )
    }

    // The assertions are inline so that Konsist, which derives the test name from the call stack, reports and
    // suppresses by the test method name.

    @Suppress("NOTHING_TO_INLINE")
    private inline fun assertNoUsages(files: List<KoFileDeclaration>, forbidden: List<String>, rule: String) {
        assertNoUsages(files, rule) { name -> name.isWithinAny(forbidden) }
    }

    /** Fails if an import or a fully qualified reference in [files] is [isForbidden]. */
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
