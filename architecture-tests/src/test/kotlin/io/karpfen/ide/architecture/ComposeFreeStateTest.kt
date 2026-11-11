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
 * The UI follows a unidirectional data flow. Composables render immutable UI state and emit intents, while the editor
 * models and every state holder are plain Kotlin that unit tests can run without Compose. The rule covers two groups of
 * packages.
 *
 * The first group is an explicit list of packages of ui-editor and ui-foundation ([COMPOSE_FREE]). It holds the
 * Compose-free editor model, the state holders of the editor, and the command registry and key model of foundation.
 * The Compose adapter of the key model lives apart in `keys.compose`. The editor model and the editor state include
 * their sub-packages (`ui.editor.model..`, `ui.editor.state..`), where features may add their own.
 *
 * The second group is every package of ui-workbench except three kinds ([isWorkbenchUiPackage]): the root package with
 * the `Workbench` composable and its wiring, the `view` sub-packages of the features, and the desktop adapters in
 * `files.desktop`. The workbench is organized by feature. Each feature package (`projecttree`, `tabs`, `outline`,
 * `problems`, `statusbar`, `dialog`, `layout`, `output`) holds its Compose-free sub-state and UI values, and its `view`
 * sub-package holds its composables. The package `state` holds the mediator that coordinates the sub-states.
 *
 * These packages must not use Compose (`androidx.compose..`, `org.jetbrains.compose..`), AWT or Swing. Composables,
 * Compose-side helper state such as a hoisted `TreeViewState`, and toolkit adapters live in view or adapter packages.
 * Those depend on the Compose-free packages, never the other way round.
 */
class ComposeFreeStateTest {

    private companion object {
        /** The Compose-free packages of ui-editor and ui-foundation. An entry ending in `..` includes its sub-packages. */
        val COMPOSE_FREE = listOf(
            "io.karpfen.ide.ui.editor.model..",
            "io.karpfen.ide.ui.editor.state..",
            "io.karpfen.ide.ui.foundation.command",
            "io.karpfen.ide.ui.foundation.keys",
        )

        /** The root package of ui-workbench. */
        const val WORKBENCH = "io.karpfen.ide.ui.workbench"

        /**
         * The Compose-free packages of ui-workbench that must exist. The list keeps a renamed feature package from
         * silently dropping out of the rule.
         */
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

    /** Guards against a vacuous rule. A renamed package would otherwise silently drop out of the rule. */
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
