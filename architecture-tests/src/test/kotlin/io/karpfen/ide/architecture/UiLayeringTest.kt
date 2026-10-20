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
    fun `workbench state holders see no views, ports see no adapters`() {
        val views = setOf("projecttree", "tabs", "outline", "problems", "statusbar", "dialog", "welcome", "files.desktop")
            .map { "$WORKBENCH.$it" }.toSet() + WORKBENCH
        assertNoEdges(
            module = "ui/ui-workbench",
            from = { it == "$WORKBENCH.state" },
            to = { it in views },
            rule = "$WORKBENCH.state must not depend on the workbench views, the root package or files.desktop",
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
