package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unidirectional data flow (spec §1a, §7.1): the editor models and every state holder are plain Kotlin, unit-testable
 * without Compose. The rule applies to an explicit list of production packages of the UI modules ([COMPOSE_FREE]):
 * the Compose-free editor model, the state-holder packages of editor and workbench, and the workbench's ports
 * (commands, file-operation ports, settings, templates) and foundation's command registry.
 *
 * Those packages must not use Compose (`androidx.compose..`, `org.jetbrains.compose..`) nor AWT/Swing; composables,
 * Compose-side helper state (e.g. a hoisted `TreeViewState`) and toolkit adapters live in view or adapter packages
 * that depend on them, never the other way round. The list is explicit (not derived from class names), so a Compose
 * helper named `*State` in a view package is not a violation; a new state-holder package is added here.
 */
class ComposeFreeStateTest {

    private companion object {
        /** Compose-free packages; an entry ending in `..` includes its sub-packages. */
        val COMPOSE_FREE = listOf(
            "io.karpfen.ide.ui.editor.model..",
            "io.karpfen.ide.ui.editor.state",
            "io.karpfen.ide.ui.workbench.state",
            "io.karpfen.ide.ui.workbench.command",
            "io.karpfen.ide.ui.workbench.files",
            "io.karpfen.ide.ui.workbench.settings",
            "io.karpfen.ide.ui.workbench.templates",
            "io.karpfen.ide.ui.foundation.command",
        )
        val UI_TOOLKITS = listOf("androidx.compose", "org.jetbrains.compose", "java.awt", "javax.swing")

        fun String.isComposeFree(): Boolean = COMPOSE_FREE.any { entry ->
            if (entry.endsWith("..")) isWithin(entry.removeSuffix("..")) else this == entry
        }
    }

    private val uiMainFiles: List<KoFileDeclaration> by lazy {
        KarpfenModules.mainFiles.filter { it.moduleDir in KarpfenModules.uiModules }
    }

    @Test
    fun `editor models and state holders do not use Compose, AWT or Swing`() {
        val files = uiMainFiles.filter { it.packageName.isComposeFree() }
        val rule = "$COMPOSE_FREE must be Compose-free (no ${UI_TOOLKITS.joinToString { "$it.." }}); " +
            "move composables and toolkit code to a view or adapter package"
        assertTrue(files.isNotEmpty(), "Rule '$rule' matched no files; is the Konsist scope broken?")

        files.flatMap { it.imports }.assertFalse(additionalMessage = rule) { import -> import.name.isWithinAny(UI_TOOLKITS) }
        val qualified = files
            .associateWith { file -> file.qualifiedReferences.filter { it.name.isWithinAny(UI_TOOLKITS) } }
            .filterValues { it.isNotEmpty() }
        files.assertFalse(
            additionalMessage = "$rule (fully qualified references: " +
                qualified.entries.joinToString { (file, references) -> "${file.projectPath}: ${references.joinToString()}" } + ")",
        ) { file -> file in qualified }
    }

    @Test
    fun `every listed Compose-free package exists`() {
        // Guards against a vacuous rule: a renamed package would silently drop out of the list.
        val packages = uiMainFiles.map { it.packageName }.toSet()
        COMPOSE_FREE.forEach { entry ->
            val found = if (entry.endsWith("..")) packages.any { it.isWithin(entry.removeSuffix("..")) } else entry in packages
            assertTrue(found, "no production files in $entry")
        }
    }
}
