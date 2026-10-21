package io.karpfen.ide.app.e2e

import io.karpfen.ide.app.IdeMenus
import io.karpfen.ide.app.menu.ResolvedMenuEntry
import io.karpfen.ide.app.menu.resolveMenus
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.tools.cli.validate.StandardOutputGuard
import io.karpfen.ide.tools.cli.validate.ValidateCommand
import io.karpfen.ide.tools.cli.validate.ValidationReport
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.foundation.command.MenuEntry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The composition root over the production wiring (INTEGRATION, plan §4): `IdeApplication.prepare` opens the folder
 * given on the command line into the real workbench, the menus resolve against its commands, and the Problems view,
 * the status bar and `karpfen validate` report the same problems for the examples plus the shared descriptor examples,
 * before and after opening and closing files (decision 17: one problems rule).
 */
class IdeEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    private fun scenario(block: suspend EndToEndIde.() -> Unit) = runTest(timeout = 120.seconds) {
        ide().use { it.block() }
    }

    private fun TestScope.ide() = EndToEndIde(testScheduler, dir.resolve("settings"))

    @Test
    fun `prepare opens the command-line folder in the real workbench and every menu item has its command`() = scenario {
        Examples.copyTo(root)

        val session = prepare(root)

        assertEquals("${root.fileName} — karpfen IDE", session.state.windowTitle.value)
        assertEquals(listOf(root.toString()), session.state.settings.value.recentFolders)
        val recent = session.state.settings.value.recentFolders.size
        val specified = IdeMenus.specs(recent).flatMap { menu -> menu.entries.flatMap(::commandIdsOf) }
        val resolved = resolveMenus(IdeMenus.specs(recent), session.commands).flatMap { menu -> menu.entries.flatMap(::commandIdsOf) }
        assertEquals(specified, resolved, "no menu item is hidden for want of a command")
        val undo = resolveMenus(IdeMenus.specs(recent), session.commands)
            .single { it.title == IdeMenus.EDIT }.entries.filterIsInstance<ResolvedMenuEntry.Item>()
            .single { it.command.id == EditorActionIds.UNDO }
        val keymapUndo = defaultEditorKeymap(HostPlatform.MAC_OS).shortcutFor(EditorActionIds.UNDO)
        assertEquals(keymapUndo, undo.accelerator, "Cmd+Z from the one keymap")
    }

    @Test
    fun `Problems, the status bar and karpfen validate agree, before and after opening and closing files`(reporter: TestReporter) =
        scenario {
            Examples.copyTo(root)
            DescriptorExamples.copyExplicitContext(root.resolve("ctx-valid"), DescriptorExamples.VALID)
            DescriptorExamples.copyExplicitContext(root.resolve("ctx-unknown"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
            DescriptorExamples.copyExplicitContext(root.resolve("ctx-invalid"), DescriptorExamples.INVALID_JSON)
            val looseMetamodel = root.resolve("${DescriptorExamples.CONTEXT_DIRECTORY}/zz.kmeta")
            Files.writeString(looseMetamodel, "type \"B\" \"b\" {\n    prop(\"y\", \"number\")\n")
            val session = prepare(root)
            val cli = validate(root)
            reporter.publishEntry("karpfen validate", cli.joinToString("\n"))

            val codes = cli.map { it.substringAfterLast('[').removeSuffix("]") }.toSet()
            assertTrue(
                codes.containsAll(
                    listOf(
                        WorkspaceDiagnosticCodes.DESCRIPTOR_INVALID,
                        WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE,
                        WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY,
                        WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS,
                    ),
                ),
                "the scenario covers descriptor, context and syntax findings: $cli",
            )
            assertAgree(session.state, cli, "after opening the folder")

            val opened = listOf(
                looseMetamodel,
                root.resolve("ctx-unknown/cleaning_robot.kstates"),
                root.resolve("${DescriptorExamples.CONTEXT_DIRECTORY}/cleaning_robot.kmodel"),
            )
            opened.forEach { session.state.openFile(it).also { settle() } }
            session.state.workspace.value!!.awaitAnalysis()
            settle()
            assertEquals(opened.size, session.state.tabs.tabs.value.size)
            assertAgree(session.state, cli, "with open files")

            opened.forEach { session.state.closeTab(it).also { settle() } }
            session.state.workspace.value!!.awaitAnalysis()
            settle()
            assertEquals(0, session.state.tabs.tabs.value.size)
            assertAgree(session.state, cli, "after closing them")
        }

    /** `karpfen validate` of [folder] as text lines (the CLI's own report, through its `ValidateCommand`). */
    private suspend fun EndToEndIde.validate(folder: Path): List<String> {
        val report = ValidateCommand(StandardOutputGuard(NOWHERE, NOWHERE), cliWorkspaces()).run(folder)
        return report.lines()
    }

    private fun assertAgree(state: WorkbenchState, cli: List<String>, situation: String) {
        val problems = state.problems.state.value
        val shown = problems.groups.flatMap { group ->
            group.items.map { item ->
                val diagnostic = item.diagnostic
                "${group.displayPath}:${item.line}:${item.column}: ${diagnostic.severity.name.lowercase()}: " +
                    "${diagnostic.message} [${diagnostic.code}]"
            }
        }
        assertEquals(cli.sorted(), shown.sorted(), "Problems view and karpfen validate $situation")
        val status = state.statusBar.state.value
        assertEquals(cli.count { ": error: " in it }, status.errorCount, "status-bar errors $situation")
        assertEquals(cli.count { ": warning: " in it }, status.warningCount, "status-bar warnings $situation")
        assertEquals(status.errorCount, problems.errorCount)
        assertEquals(status.warningCount, problems.warningCount)
    }

    private fun ValidationReport.lines(): List<String> = problems.map {
        "${it.path}:${it.line}:${it.column}: ${it.severity.name.lowercase()}: ${it.message} [${it.code}]"
    }

    private fun commandIdsOf(entry: MenuEntry): List<String> = when (entry) {
        is MenuEntry.Item -> listOf(entry.commandId)
        is MenuEntry.Submenu -> entry.entries.flatMap(::commandIdsOf)
        MenuEntry.Separator -> emptyList()
    }

    private fun commandIdsOf(entry: ResolvedMenuEntry): List<String> = when (entry) {
        is ResolvedMenuEntry.Item -> listOf(entry.command.id)
        is ResolvedMenuEntry.Submenu -> entry.entries.flatMap(::commandIdsOf)
        ResolvedMenuEntry.Separator -> emptyList()
    }

    private companion object {
        /** Swallows the CLI's guard output (the report is compared, not printed). */
        val NOWHERE = PrintStream(ByteArrayOutputStream())
    }
}
