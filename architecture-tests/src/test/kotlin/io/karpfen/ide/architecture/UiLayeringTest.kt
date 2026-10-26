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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dependency direction inside the UI modules: the key model never sees its Compose adapter; the Compose-free editor
 * model sees no other editor package; the editor view and the popups meet only through the overlay slot API, so each
 * can change without the other; the editor and workbench state holders never see their views or desktop adapters; the
 * workbench's feature sub-states never see the mediator that coordinates them; ports never see their adapters.
 * Checked on the package graph of the production code ([PackageGraph]).
 */
class UiLayeringTest {

    private companion object {
        /** The root package of ui-editor. */
        const val EDITOR = "io.karpfen.ide.ui.editor"

        /** The root package of ui-workbench. */
        const val WORKBENCH = "io.karpfen.ide.ui.workbench"

        /** The root package of ui-foundation. */
        const val FOUNDATION = "io.karpfen.ide.ui.foundation"

        /**
         * The workbench feature packages, each holding a Compose-free sub-state and its UI values. The mediator in
         * `state` coordinates the sub-states, so the dependency only runs from the mediator to them.
         */
        val WORKBENCH_FEATURES = listOf("projecttree", "tabs", "outline", "problems", "statusbar", "dialog", "layout", "output")
            .map { "$WORKBENCH.$it" }

        /** Workbench packages that may use Compose: the root, every `view` package, the desktop adapters. */
        fun String.isWorkbenchUiPackage(): Boolean =
            this == WORKBENCH || endsWith(".view") || isWithin("$WORKBENCH.files.desktop")
    }

    @Test
    fun `the key model does not depend on its Compose adapter`() {
        assertNoEdges(
            module = "ui/ui-foundation",
            from = { it == "$FOUNDATION.keys" || it == "$FOUNDATION.command" },
            to = { it.isWithin("$FOUNDATION.keys.compose") },
            rule = "$FOUNDATION.keys and .command must not depend on $FOUNDATION.keys.compose (the Compose adapter)",
        )
    }

    @Test
    fun `the editor model depends on no other editor package`() {
        assertNoEdges(
            module = "ui/ui-editor",
            from = { it.isWithin("$EDITOR.model") },
            to = { !it.isWithin("$EDITOR.model") && it.isWithin(EDITOR) },
            rule = "$EDITOR.model.. must not depend on state, overlay, view, popup or theme",
        )
    }

    /**
     * Sub-packages count as their parent, so a sub-package such as `view.render` or `popup.find` cannot bypass the
     * rule. The editor state holders see neither side, nor the overlay or the theme.
     */
    @Test
    fun `the editor view and the popups meet only through the overlay slot API`() {
        assertNoEdges(
            module = "ui/ui-editor",
            from = { it.isWithin("$EDITOR.view") },
            to = { it.isWithin("$EDITOR.popup") },
            rule = "$EDITOR.view.. must not depend on $EDITOR.popup..; use $EDITOR.overlay",
        )
        assertNoEdges(
            module = "ui/ui-editor",
            from = { it.isWithin("$EDITOR.popup") },
            to = { it.isWithin("$EDITOR.view") },
            rule = "$EDITOR.popup.. must not depend on $EDITOR.view..; use $EDITOR.overlay",
        )
        assertNoEdges(
            module = "ui/ui-editor",
            from = { it.isWithin("$EDITOR.state") },
            to = { target -> listOf("view", "popup", "overlay", "theme").any { target.isWithin("$EDITOR.$it") } },
            rule = "$EDITOR.state.. must not depend on view, popup, overlay or theme (or their sub-packages)",
        )
    }

    @Test
    fun `workbench state holders see no views, sub-states see no mediator, ports see no adapters`() {
        assertNoEdges(
            module = "ui/ui-workbench",
            from = { it.isWithin(WORKBENCH) && !it.isWorkbenchUiPackage() },
            to = { it.isWorkbenchUiPackage() },
            rule = "Compose-free workbench packages must not depend on view packages, the root package or files.desktop",
        )
        assertNoEdges(
            module = "ui/ui-workbench",
            from = { it in WORKBENCH_FEATURES },
            to = { it == "$WORKBENCH.state" },
            rule = "the feature sub-states must not depend on the mediator package $WORKBENCH.state",
        )
        assertNoEdges(
            module = "ui/ui-workbench",
            from = { it == "$WORKBENCH.files" },
            to = { it == "$WORKBENCH.files.desktop" },
            rule = "the ports in $WORKBENCH.files must not depend on their desktop adapters",
        )
    }

    /**
     * Fails if a package of [module] that matches [from] depends on a package that matches [to], and if no package
     * matches [from], so a renamed package cannot make the rule vacuous.
     */
    private fun assertNoEdges(module: String, from: (String) -> Boolean, to: (String) -> Boolean, rule: String) {
        val graph = PackageGraph.ofModule(module)
        assertTrue(graph.nodes.any(from), "Rule '$rule' matched no package of $module; is the scope broken?")
        val violations = graph.edges.filterKeys(from).flatMap { (source, targets) -> targets.filter(to).map { "$source -> $it" } }
        assertTrue(violations.isEmpty(), "$rule: $violations")
    }
}
