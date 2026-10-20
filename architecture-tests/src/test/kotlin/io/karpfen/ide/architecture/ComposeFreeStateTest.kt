package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unidirectional data flow (spec §1a, §7.1): the editor models and every state holder are plain Kotlin, unit-testable
 * without Compose. The rule applies to
 * - an explicit list of packages of ui-editor and ui-foundation ([COMPOSE_FREE]): the Compose-free editor model, the
 *   editor's state holders, foundation's command registry and key model (its Compose adapter is `keys.compose`);
 * - every package of ui-workbench except the root package (the `Workbench` composable and its wiring), the features'
 *   `view` sub-packages and the desktop adapters `files.desktop` ([isWorkbenchUiPackage]): the workbench is organized
 *   by feature (decision 31 of the work-package plan), each feature package holding its Compose-free state and a
 *   `view` sub-package holding its composables.
 *
 * Those packages must not use Compose (`androidx.compose..`, `org.jetbrains.compose..`) nor AWT/Swing; composables,
 * Compose-side helper state (e.g. a hoisted `TreeViewState`) and toolkit adapters live in view or adapter packages
 * that depend on them, never the other way round.
 */
class ComposeFreeStateTest {

    private companion object {
        /** Compose-free packages of ui-editor and ui-foundation; an entry ending in `..` includes its sub-packages. */
        val COMPOSE_FREE = listOf(
            "io.karpfen.ide.ui.editor.model..",
            "io.karpfen.ide.ui.editor.state",
            "io.karpfen.ide.ui.foundation.command",
            "io.karpfen.ide.ui.foundation.keys",
        )

        const val WORKBENCH = "io.karpfen.ide.ui.workbench"

        /** Compose-free packages of ui-workbench that must exist (guards the by-feature rule against renames). */
        val WORKBENCH_STATE_PACKAGES = listOf(
            "state", "projecttree", "tabs", "outline", "problems", "statusbar", "dialog", "layout",
            "command", "files", "settings", "templates",
        ).map { "$WORKBENCH.$it" }

        val UI_TOOLKITS = listOf("androidx.compose", "org.jetbrains.compose", "java.awt", "javax.swing")

        /** The workbench packages that may use Compose: the root, every `view` package, the desktop adapters. */
        fun String.isWorkbenchUiPackage(): Boolean =
            this == WORKBENCH || endsWith(".view") || isWithin("$WORKBENCH.files.desktop")

        fun String.isComposeFree(): Boolean =
            COMPOSE_FREE.any { entry -> if (entry.endsWith("..")) isWithin(entry.removeSuffix("..")) else this == entry } ||
                (isWithin(WORKBENCH) && !isWorkbenchUiPackage())
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
        (COMPOSE_FREE + WORKBENCH_STATE_PACKAGES).forEach { entry ->
            val found = if (entry.endsWith("..")) packages.any { it.isWithin(entry.removeSuffix("..")) } else entry in packages
            assertTrue(found, "no production files in $entry")
        }
        assertTrue(packages.filter { it.isWithin(WORKBENCH) }.any { it.endsWith(".view") }, "no workbench view packages")
    }
}
