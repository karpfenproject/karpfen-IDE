package io.karpfen.ide.app.e2e

import androidx.compose.ui.input.key.Key
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.karpfen.ide.app.IdeMenus
import io.karpfen.ide.app.IdePhase
import io.karpfen.ide.app.logging.LanguageToolsLogRouting
import io.karpfen.ide.app.menu.ResolvedMenuEntry
import io.karpfen.ide.app.menu.resolveMenus
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
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
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.compose.toMenuShortcut
import io.karpfen.ide.ui.foundation.keys.displayText
import io.karpfen.ide.ui.workbench.command.WorkbenchCommandIds
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.output.OutputLevel
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.input.key.KeyShortcut as ComposeKeyShortcut

/**
 * The composition root over the production wiring: `IdeApplication.prepare` opens the folder given on the command line
 * into the real workbench, the menus resolve against its commands, and the Problems view, the status bar and
 * `karpfen validate` report the same problems for the examples plus the shared descriptor examples, before and after
 * opening and closing files (descriptors included), because all three map `Workspace.problems`, the one problems
 * rule. A command-line folder that `main` saw as a directory may be gone (unmounted, deleted) by the time `prepare`
 * opens it. On every platform the Edit menu resolves Complete Code with the accelerator Ctrl+M, written in that
 * platform's notation, and the stroke finds that command. What dsl-tools logs while the IDE analyzes reaches the
 * Language Tools Output through the composition root, and the View menu opens it.
 */
class IdeEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    private fun scenario(platform: HostPlatform = HostPlatform.MAC_OS, block: suspend EndToEndIde.() -> Unit) =
        runTest(timeout = 120.seconds) {
            ide(platform).use { it.block() }
        }

    private fun TestScope.ide(platform: HostPlatform) = EndToEndIde(testScheduler, dir.resolve("settings"), platform)

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

    @ParameterizedTest
    @EnumSource(HostPlatform::class)
    fun `the Edit menu shows Complete Code with Ctrl+M in the platform's notation`(platform: HostPlatform) =
        scenario(platform) {
            val session = prepare(Files.createDirectories(root))
            val recent = session.state.settings.value.recentFolders.size

            val edit = resolveMenus(IdeMenus.specs(recent), session.commands).single { it.title == IdeMenus.EDIT }
            val completion = edit.entries.filterIsInstance<ResolvedMenuEntry.Item>()
                .singleOrNull { it.command.id == EditorActionIds.COMPLETION }
            val item = assertNotNull(completion, "Edit lists Complete Code on $platform")
            assertEquals("Complete Code", item.title)
            val accelerator = assertNotNull(item.accelerator, "with an accelerator on $platform")
            assertEquals(KeyShortcut(KeyCode.M, ctrl = true), accelerator)
            assertEquals(COMPLETION_SHORTCUT_TEXT.getValue(platform), accelerator.displayText(platform))
            assertEquals(ComposeKeyShortcut(Key.M, ctrl = true), accelerator.toMenuShortcut(platform), "the native menu's shortcut")
            assertSame(item.command, session.commands.commandFor(KeyStroke(KeyCode.M, setOf(KeyModifier.CTRL))))
        }

    @Test
    fun `a command-line folder that vanished before the start opens the window with the error dialog`() = scenario {
        val vanished = Files.createDirectories(dir.resolve("vanished"))
        Files.delete(vanished)

        val run = startRun(vanished)
        settle()

        val running = assertIs<IdePhase.Running>(run.phase.value, "the startup does not wait for the dialog")
        val state = running.session.state
        assertEquals("Cannot open folder", assertIs<DialogRequest.Error>(state.dialogs.current.value).title)
        state.dialogs.respond(DialogResponse.Confirm)
        settle()
        assertNull(state.dialogs.current.value)
        assertNull(state.workspace.value, "the empty workbench with Open Folder… and the recent folders")
        run.exit()
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
                        DiagnosticCodes.SYNTAX_ERROR,
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
                root.resolve("ctx-unknown/${DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE}"),
                root.resolve("ctx-invalid/${DescriptorExamples.INVALID_JSON}"),
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

    @Test
    fun `what dsl-tools logs while the IDE analyzes is shown in the Language Tools Output, which the View menu opens`() =
        scenario {
            Examples.copyTo(root)
            val context = assertIs<LoggerContext>(LoggerFactory.getILoggerFactory())
            val session = withDslToolsLoggingAtDebug(context) {
                routeLanguageToolsLog(LanguageToolsLogRouting(context)).use { prepare(root) }
            }
            val output = session.state.output
            val recent = session.state.settings.value.recentFolders.size
            val view = resolveMenus(IdeMenus.specs(recent), session.commands).single { it.title == IdeMenus.VIEW }
            val item = view.entries.filterIsInstance<ResolvedMenuEntry.Item>()
                .single { it.command.id == WorkbenchCommandIds.toggleToolWindow(ToolWindowId.OUTPUT) }
            assertFalse(session.state.layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible, "hidden by default")

            assertEquals("Language Tools Output", item.title)
            assertTrue(item.invoke())
            settle()

            assertTrue(session.state.layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
            val converters = output.state.value.lines.filter { it.logger.endsWith("DSLConverter") }
            assertTrue(converters.isNotEmpty(), "the converters logged what they converted: ${output.state.value.lines.take(5)}")
            assertTrue(converters.all { it.level == OutputLevel.DEBUG })
            assertTrue(output.state.value.unseen.isEmpty)
        }

    /**
     * Runs [block] with the dsl-tools loggers of [context] at DEBUG and passing nothing on to the test console, as the
     * production configuration has them, and restores both afterwards.
     */
    private inline fun <T> withDslToolsLoggingAtDebug(context: LoggerContext, block: () -> T): T {
        val logger = context.getLogger(DslToolsLoggers.PARENT)
        val level = logger.level
        val additive = logger.isAdditive
        logger.level = Level.DEBUG
        logger.isAdditive = false
        try {
            return block()
        } finally {
            logger.level = level
            logger.isAdditive = additive
        }
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

        /** How each platform writes the completion shortcut in its menus. */
        val COMPLETION_SHORTCUT_TEXT: Map<HostPlatform, String> = mapOf(
            HostPlatform.MAC_OS to "⌃M",
            HostPlatform.WINDOWS to "Ctrl+M",
            HostPlatform.LINUX to "Ctrl+M",
        )
    }
}
