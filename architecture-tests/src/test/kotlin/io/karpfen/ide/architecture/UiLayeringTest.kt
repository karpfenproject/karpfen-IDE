package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dependency direction inside the UI modules (spec §1a, §7): the Compose-free editor model sees no other editor
 * package; the editor view and the popups (built in parallel by different work packages) meet only through the
 * overlay slot API; the workbench's state holders never see its views or desktop adapters; ports never see their
 * adapters. Checked on the package graph of the production code ([PackageGraph]).
 */
class UiLayeringTest {

    private companion object {
        const val EDITOR = "io.karpfen.ide.ui.editor"
        const val WORKBENCH = "io.karpfen.ide.ui.workbench"
        const val FOUNDATION = "io.karpfen.ide.ui.foundation"

        /** The workbench feature packages holding sub-states (decision 31 of the work-package plan). */
        val WORKBENCH_FEATURES = listOf("projecttree", "tabs", "outline", "problems", "statusbar", "dialog", "layout")
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
            rule = "$EDITOR.model.. must not depend on state, overlay, view, popup or theme (spec §7.1)",
        )
    }

    @Test
    fun `the editor view and the popups meet only through the overlay slot API`() {
        assertNoEdges(
            module = "ui/ui-editor",
            from = { it == "$EDITOR.view" || it == "$EDITOR.popup" },
            to = { it == "$EDITOR.view" || it == "$EDITOR.popup" },
            rule = "$EDITOR.view and $EDITOR.popup must not depend on each other; use $EDITOR.overlay",
        )
        assertNoEdges(
            module = "ui/ui-editor",
            from = { it == "$EDITOR.state" },
            to = { it in setOf("$EDITOR.view", "$EDITOR.popup", "$EDITOR.overlay", "$EDITOR.theme") },
            rule = "$EDITOR.state must not depend on view, popup, overlay or theme",
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

    private fun assertNoEdges(module: String, from: (String) -> Boolean, to: (String) -> Boolean, rule: String) {
        val graph = PackageGraph.ofModule(module)
        assertTrue(graph.nodes.any(from), "Rule '$rule' matched no package of $module; is the scope broken?")
        val violations = graph.edges.filterKeys(from).flatMap { (source, targets) -> targets.filter(to).map { "$source -> $it" } }
        assertTrue(violations.isEmpty(), "$rule: $violations")
    }
}
