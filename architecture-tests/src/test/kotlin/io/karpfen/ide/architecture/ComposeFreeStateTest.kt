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
 * The UI follows a unidirectional data flow: composables render immutable UI state and emit intents, while the editor
 * models and every state holder are plain Kotlin, unit-testable without Compose. The rule applies to
 * - an explicit list of packages of ui-editor and ui-foundation ([COMPOSE_FREE]): the Compose-free editor model, the
 *   editor's state holders, foundation's command registry and key model (its Compose adapter is `keys.compose`); the
 *   editor model and state include their sub-packages, where features may add their own (`ui.editor.model..`,
 *   `ui.editor.state..`);
 * - every package of ui-workbench except the root package (the `Workbench` composable and its wiring), the features'
 *   `view` sub-packages and the desktop adapters `files.desktop` ([isWorkbenchUiPackage]). The workbench is organized
 *   by feature: each feature package (`projecttree`, `tabs`, `outline`, `problems`, `statusbar`, `dialog`, `layout`,
 *   `output`) holds its Compose-free sub-state and UI values and a `view` sub-package holding its composables, and
 *   `state` holds the mediator that coordinates the sub-states.
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
            "io.karpfen.ide.ui.editor.state..",
            "io.karpfen.ide.ui.foundation.command",
            "io.karpfen.ide.ui.foundation.keys",
        )

        /** The root package of ui-workbench. */
        const val WORKBENCH = "io.karpfen.ide.ui.workbench"

        /** Compose-free packages of ui-workbench that must exist (guards the by-feature rule against renames). */
        val WORKBENCH_STATE_PACKAGES = listOf(
            "state", "projecttree", "tabs", "outline", "problems", "statusbar", "dialog", "layout", "output",
            "command", "files", "settings", "templates", "runtime",
        ).map { "$WORKBENCH.$it" }

        /** The UI toolkits that the Compose-free packages must not use. */
        val UI_TOOLKITS = listOf("androidx.compose", "org.jetbrains.compose", "java.awt", "javax.swing")

        /** The workbench packages that may use Compose: the root, every `view` package, the desktop adapters. */
        fun String.isWorkbenchUiPackage(): Boolean =
            this == WORKBENCH || endsWith(".view") || isWithin("$WORKBENCH.files.desktop")

        /**
         * True if this package must not use a UI toolkit: it is listed in [COMPOSE_FREE], or it is a workbench package
         * that [isWorkbenchUiPackage] does not exempt.
         */
        fun String.isComposeFree(): Boolean =
            COMPOSE_FREE.any { entry -> if (entry.endsWith("..")) isWithin(entry.removeSuffix("..")) else this == entry } ||
                (isWithin(WORKBENCH) && !isWorkbenchUiPackage())
    }

    /** The production files of the UI modules. */
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

    /** Guards against a vacuous rule: a renamed package would silently drop out of the list. */
    @Test
    fun `every listed Compose-free package exists`() {
        val packages = uiMainFiles.map { it.packageName }.toSet()
        (COMPOSE_FREE + WORKBENCH_STATE_PACKAGES).forEach { entry ->
            val found = if (entry.endsWith("..")) packages.any { it.isWithin(entry.removeSuffix("..")) } else entry in packages
            assertTrue(found, "no production files in $entry")
        }
        assertTrue(packages.filter { it.isWithin(WORKBENCH) }.any { it.endsWith(".view") }, "no workbench view packages")
    }
}
