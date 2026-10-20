package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Import rules that keep the layers apart (D4, D5, D6, D7 in docs/architecture-proposal.md).
 * They apply to main and test sources of every module, identified by package.
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
        assertNoImports(
            files = KarpfenModules.filesInPackage(CORE),
            forbidden = listOf(NOTATION, RUNTIME, BRIDGES, UI, APP, TOOLS) + INFRASTRUCTURE,
            rule = "$CORE.. must not import notation, runtime, bridges, ui, app, tools, " +
                "Compose, Ktor, Docker, HiveMQ, JavaFX, AWT or Swing",
        )
    }

    @Test
    fun `notation may use core but no other layer and no UI or infrastructure library`() {
        assertNoImports(
            files = KarpfenModules.filesInPackage(NOTATION),
            forbidden = listOf(RUNTIME, BRIDGES, UI, APP, TOOLS) + INFRASTRUCTURE,
            rule = "$NOTATION.. must not import runtime, bridges, ui, app, tools, Compose, Ktor, Docker, HiveMQ, JavaFX, AWT or Swing",
        )
    }

    @Test
    fun `runtime is independent of ui, notation, bridges, app, tools and UI toolkits`() {
        assertNoImports(
            files = KarpfenModules.filesInPackage(RUNTIME),
            forbidden = listOf(UI, NOTATION, BRIDGES, APP, TOOLS) + DESKTOP_UI_TOOLKITS,
            rule = "$RUNTIME.. must not import ui, notation, bridges, app, tools, Compose, JavaFX, AWT or Swing",
        )
    }

    @Test
    fun `bridges use only the runtime protocol and no ui, notation, app or tools`() {
        val files = KarpfenModules.filesInPackage(BRIDGES)
        assertTrue(files.isNotEmpty(), "No files found in $BRIDGES..; is the Konsist scope broken?")
        files.flatMap { it.imports }.assertFalse(
            additionalMessage = "$BRIDGES.. may import $RUNTIME_PROTOCOL.. but no other runtime package, " +
                "and not ui, notation, app or tools",
        ) { import ->
            (import.name.isWithin(RUNTIME) && !import.name.isWithin(RUNTIME_PROTOCOL)) ||
                import.name.isWithinAny(listOf(UI, NOTATION, APP, TOOLS))
        }
    }

    @Test
    fun `only runtime-host may use the Docker client`() {
        assertNoImports(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(RUNTIME_HOST) },
            forbidden = listOf(DOCKER),
            rule = "Only $RUNTIME_HOST.. may import $DOCKER..",
        )
    }

    @Test
    fun `only bridge-mqtt may use the HiveMQ client`() {
        assertNoImports(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithin(BRIDGES_MQTT) },
            forbidden = listOf(HIVEMQ),
            rule = "Only $BRIDGES_MQTT.. may import $HIVEMQ..",
        )
    }

    @Test
    fun `Compose is confined to ui and app`() {
        assertNoImports(
            files = KarpfenModules.moduleFiles.filterNot { it.packageName.isWithinAny(listOf(UI, APP)) },
            forbidden = COMPOSE,
            rule = "Compose (D4) may only be imported by $UI.. and $APP..",
        )
    }

    @Test
    fun `nothing imports JavaFX`() {
        assertNoImports(
            files = KarpfenModules.moduleFiles,
            forbidden = listOf("javafx"),
            rule = "JavaFX is not used anywhere (D4: Compose Multiplatform for Desktop)",
        )
    }

    private fun assertNoImports(files: List<KoFileDeclaration>, forbidden: List<String>, rule: String) {
        assertTrue(files.isNotEmpty(), "Rule '$rule' matched no files; is the Konsist scope broken?")
        files.flatMap { it.imports }.assertFalse(additionalMessage = rule) { import -> import.name.isWithinAny(forbidden) }
    }
}
