package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unidirectional data flow (spec §1a, §7.1): the editor models and every state holder are plain Kotlin, unit-testable
 * without Compose. Checked for the production code of the UI modules:
 * - `io.karpfen.ide.ui.editor.model..` (the Compose-free editor model, spec §7.1), and
 * - every *state holder package*: a package that declares a non-private class, interface or object whose name ends
 *   in `State` (e.g. `CodeEditorState`, `WorkbenchState`, `ProblemsUiState`).
 *
 * Such packages must not use Compose (`androidx.compose..`, `org.jetbrains.compose..`) nor AWT/Swing; composables and
 * toolkit adapters live in view packages that depend on them, never the other way round.
 */
class ComposeFreeStateTest {

    private companion object {
        const val EDITOR_MODEL = "io.karpfen.ide.ui.editor.model"
        val UI_TOOLKITS = listOf("androidx.compose", "org.jetbrains.compose", "java.awt", "javax.swing")
    }

    private val uiMainFiles: List<KoFileDeclaration> by lazy {
        KarpfenModules.mainFiles.filter { it.moduleDir in KarpfenModules.uiModules }
    }

    /** Packages declaring a non-private top-level type named `*State`. */
    private val stateHolderPackages: Set<String> by lazy {
        uiMainFiles.filter { file -> file.declaresStateHolder() }.map { it.packageName }.toSet()
    }

    @Test
    fun `editor models and state holders do not use Compose, AWT or Swing`() {
        val files = uiMainFiles.filter { it.packageName.isWithin(EDITOR_MODEL) || it.packageName in stateHolderPackages }
        val rule = "$EDITOR_MODEL.. and state holder packages ($stateHolderPackages) must be Compose-free " +
            "(no ${UI_TOOLKITS.joinToString { "$it.." }}); move composables and toolkit code to a view package"
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
    fun `the state holder packages of editor and workbench are recognized`() {
        // Guards against a vacuous rule: the known state holders must be found by the naming convention.
        val expected = setOf("io.karpfen.ide.ui.editor.state", "io.karpfen.ide.ui.workbench.state")
        assertTrue(stateHolderPackages.containsAll(expected), "state holder packages found: $stateHolderPackages")
        assertTrue(uiMainFiles.any { it.packageName.isWithin(EDITOR_MODEL) }, "no files in $EDITOR_MODEL")
    }

    private fun KoFileDeclaration.declaresStateHolder(): Boolean =
        classes().any { it.name.endsWith("State") && !it.hasPrivateModifier } ||
            interfaces().any { it.name.endsWith("State") && !it.hasPrivateModifier } ||
            objects().any { it.name.endsWith("State") && !it.hasPrivateModifier }
}
