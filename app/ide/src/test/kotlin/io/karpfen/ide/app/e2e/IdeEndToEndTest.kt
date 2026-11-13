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
package io.karpfen.ide.app.e2e

import androidx.compose.ui.input.key.Key
import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.app.IdeMenus
import io.karpfen.ide.app.IdePhase
import io.karpfen.ide.app.logging.ApplicationLogging
import io.karpfen.ide.app.menu.ResolvedMenuEntry
import io.karpfen.ide.app.menu.resolveMenus
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.DslToolsLoggers
import io.karpfen.ide.core.document.fixtures.DeepNesting
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.SmallStackDslReader
import io.karpfen.ide.core.document.parse.NestingLimit
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.validation.ProjectValidation
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
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettingsFile
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
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
 * The composition root over the production wiring. `IdeApplication.prepare` opens the folder given on the command line
 * into the real workbench, and the menus resolve against its commands. The Problems view, the status bar and the
 * validation of the whole project report the same problems for the examples plus the shared descriptor examples. They
 * agree before and after files are opened and closed, descriptors included, because all three map
 * `Workspace.problems`, the one rule for problems. The validation adds the findings of the checks across files. The
 * project switches on every optional language feature. A command-line folder that `main` saw as a directory may be
 * gone, unmounted or deleted, by the time `prepare` opens it. On every platform the Edit menu resolves Complete Code with the accelerator Ctrl+M, written
 * in that platform's notation, and the stroke finds that command. What dsl-tools logs while the IDE analyzes, here the
 * failure of a reader, reaches the Language Tools Output through the composition root, and the View menu opens it.
 */
class IdeEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    private fun scenario(
        platform: HostPlatform = HostPlatform.MAC_OS,
        reader: DslReader = DslReader(),
        block: suspend EndToEndIde.() -> Unit,
    ) = runTest(timeout = 120.seconds) {
        ide(platform, reader).use { it.block() }
    }

    private fun TestScope.ide(platform: HostPlatform, reader: DslReader) =
        EndToEndIde(testScheduler, dir.resolve("settings"), platform, reader)

    @Test
    fun `prepare opens the command-line folder in the real workbench and every menu item has its command`() = scenario {
        Examples.copyTo(root)

        val session = prepare(root)

        assertEquals("${root.fileName} - karpfen IDE", session.state.windowTitle.value)
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
    fun `Problems, the status bar and the validation agree, before and after opening and closing files`(reporter: TestReporter) =
        scenario {
            Examples.copyTo(root)
            DescriptorExamples.copyExplicitContext(root.resolve("ctx-valid"), DescriptorExamples.VALID)
            DescriptorExamples.copyExplicitContext(root.resolve("ctx-unknown"), DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE)
            DescriptorExamples.copyExplicitContext(root.resolve("ctx-invalid"), DescriptorExamples.INVALID_JSON)
            val looseMetamodel = root.resolve("${DescriptorExamples.CONTEXT_DIRECTORY}/zz.kmeta")
            Files.writeString(looseMetamodel, "type \"B\" \"b\" {\n    prop(\"y\", \"number\")\n")
            switchOnEveryLanguageFeature()
            val session = prepare(root)
            val validated = session.state.workspace.value!!.validate().lines()
            reporter.publishEntry("the validation", validated.joinToString("\n"))

            val codes = validated.map { it.substringAfterLast('[').removeSuffix("]") }.toSet()
            assertTrue(
                codes.containsAll(
                    listOf(
                        DiagnosticCodes.SYNTAX_ERROR,
                        WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE,
                        WorkspaceDiagnosticCodes.DESCRIPTOR_UNKNOWN_KEY,
                        WorkspaceDiagnosticCodes.CONTEXT_MULTIPLE_METAMODELS,
                    ),
                ),
                "the scenario covers descriptor, context and syntax findings: $validated",
            )
            assertAgree(session.state, validated, "after opening the folder")
            assertEquals(
                EXAMPLE_FINDINGS_ACROSS_FILES,
                validated.filter(::isAcrossFiles).map { it.substringBefore(": error:") },
                "the checks across files find the SET on relations in the cleaning robot of dsl-tools",
            )

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
            assertAgree(session.state, validated, "with open files")

            opened.forEach { session.state.closeTab(it).also { settle() } }
            session.state.workspace.value!!.awaitAnalysis()
            settle()
            assertEquals(0, session.state.tabs.tabs.value.size)
            assertAgree(session.state, validated, "after closing them")
        }

    /**
     * What dsl-tools logs while the IDE analyzes is here the failure of a real reader. A KStates text within the
     * nesting limit nests deeper than the reader can follow on the small stack it reads with, [SmallStackDslReader].
     * The IDE's syntax analysis still follows it on the large stack the scenario runs on. dsl-tools logs the failure as
     * a warning. The warning reaches the Language Tools Output through the composition root, and while the output is
     * hidden, it also reaches the status bar's indicator.
     */
    @Test
    fun `what dsl-tools logs is shown in the Language Tools Output, which the View menu opens`() =
        DeepNesting.onStack(DeepNesting.LARGE_STACK) {
            scenario(reader = SmallStackDslReader()) {
                deepContext()
                val session = ApplicationLogging.withDslToolsAtDebug { prepare(root) }
                settle()
                val output = session.state.output
                val recent = session.state.settings.value.recentFolders.size
                val view = resolveMenus(IdeMenus.specs(recent), session.commands).single { it.title == IdeMenus.VIEW }
                val item = view.entries.filterIsInstance<ResolvedMenuEntry.Item>()
                    .single { it.command.id == WorkbenchCommandIds.toggleToolWindow(ToolWindowId.OUTPUT) }
                assertFalse(session.state.layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible, "hidden by default")
                assertTrue(session.state.statusBar.state.value.unseenOutput.warnings > 0, "the indicator counts the warning")

                assertEquals("Language Tools Output", item.title)
                assertTrue(item.invoke())
                settle()

                assertTrue(session.state.layout.state.value.toolWindow(ToolWindowId.OUTPUT).visible)
                val failures = output.state.value.lines.filter { it.logger == KSTATES_READER_LOGGER }
                assertTrue(failures.isNotEmpty(), "the reader's failure is shown: ${output.state.value.lines.take(5)}")
                assertTrue(failures.all { it.level == OutputLevel.WARNING && READER_LIMIT in it.message }, "$failures")
                assertTrue(output.state.value.unseen.isEmpty)
                assertTrue(session.state.statusBar.state.value.unseenOutput.isEmpty)
            }
        }

    /**
     * An unchanged file that a reader of dsl-tools fails on is read once however often the workspace is analyzed, so
     * the Language Tools Output and the status bar's indicator show its failure once, and the line names the file.
     */
    @Test
    fun `the reader failure of an unchanged file is shown once, with the file`() =
        DeepNesting.onStack(DeepNesting.LARGE_STACK) {
            scenario(reader = SmallStackDslReader()) {
                val machine = deepContext().resolve("deep.kstates")
                val session = ApplicationLogging.withDslToolsAtDebug {
                    prepare(root).also { prepared -> repeat(3) { checkNotNull(prepared.state.workspace.value).awaitAnalysis() } }
                }
                settle()

                val failures = session.state.output.state.value.lines.filter { it.logger == KSTATES_READER_LOGGER }
                assertEquals(listOf(machine.toString()), failures.map { it.file })
                assertEquals(1L, session.state.statusBar.state.value.unseenOutput.warnings)
                val problems = session.state.problems.state.value.local.flatMap { group -> group.items.map { it.diagnostic.code } }
                assertEquals(listOf(READER_FAILURE), problems, "the failure is still reported")
            }
        }

    /**
     * Writes a modeling context into the folder `deep` of the workspace: a metamodel and a state machine whose action
     * nests [READER_DEPTH] guards deep, more than a reader of dsl-tools follows on [DeepNesting.SMALL_STACK]. Returns
     * the folder.
     */
    private fun deepContext(): Path {
        val folder = Files.createDirectories(root.resolve("deep"))
        Files.writeString(folder.resolve("deep.kmeta"), "type \"T\" \"t\" {\n}\n")
        Files.writeString(folder.resolve("deep.kstates"), DeepNesting.guardedKstates(READER_DEPTH))
        return folder
    }

    /**
     * Writes the project file of the workspace with every optional language feature of KStates switched on, as the IDE
     * writes it after the user switched them on in the Run menu.
     */
    private fun switchOnEveryLanguageFeature() {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve(ProjectSettings.FILE_NAME),
            ProjectSettingsFile.format(ProjectSettings(languageFeatures = KstatesFeature.ALL)),
        )
    }

    /**
     * Asserts that the Problems view and the status bar show the findings of [validated] that the analysis finds, which
     * are all but the checks across files, in [situation].
     */
    private fun assertAgree(state: WorkbenchState, validated: List<String>, situation: String) {
        val problems = state.problems.state.value
        val shown = problems.local.flatMap { group ->
            group.items.map { item ->
                val diagnostic = item.diagnostic
                "${group.displayPath}:${item.line}:${item.column}: ${diagnostic.severity.name.lowercase()}: " +
                    "${diagnostic.message} [${diagnostic.code}]"
            }
        }
        val analyzed = validated.filterNot(::isAcrossFiles)
        assertEquals(analyzed.sorted(), shown.sorted(), "Problems view and the validation without the checks across files $situation")
        val status = state.statusBar.state.value
        assertEquals(analyzed.count { ": error: " in it }, status.errorCount, "status-bar errors $situation")
        assertEquals(analyzed.count { ": warning: " in it }, status.warningCount, "status-bar warnings $situation")
        assertEquals(status.errorCount, problems.errorCount)
        assertEquals(status.warningCount, problems.warningCount)
    }

    /** True if [line], made by [lines], reports a check across files, which only a validation runs. */
    private fun isAcrossFiles(line: String): Boolean = line.endsWith("]") && line.substringAfterLast('[').startsWith("project.")

    /**
     * The findings of this validation, one line each in the form `path:line:column: severity: message [code]`, with the
     * path relative to the root.
     */
    private fun ProjectValidation.lines(): List<String> = findings.map {
        "${root.relativize(it.path).invariantSeparatorsPathString}:${it.line}:${it.column}: ${it.severity.name.lowercase()}: " +
            "${it.message} [${it.code}]"
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
        /**
         * Where the checks across files find an error in the examples. The cleaning robot of dsl-tools sets the
         * relations `closest_obstacle`, `closest_wall` and `direction` with SET, which takes a property.
         */
        val EXAMPLE_FINDINGS_ACROSS_FILES = listOf(13 to 22, 15 to 22, 24 to 26, 33 to 26).map { (line, column) ->
            "karpfen-dsl-tools/statemachine_full_example/cleaning_robot.kstates:$line:$column"
        }

        /** The logger of the KStates reader of dsl-tools, which logs a read that fails. */
        const val KSTATES_READER_LOGGER = "${DslToolsLoggers.PARENT}.reader.kstates.KstatesReader"

        /** What dsl-tools logs when a text nests deeper than its reader can follow. */
        const val READER_LIMIT = "Reading a text failed, it nests deeper than the parser can recurse"

        /** The code of dsl-tools for a read that failed. */
        const val READER_FAILURE = "reader.failure"

        /**
         * How many guards the action of the reader's failure nests in. That is too many for a dsl-tools reader on
         * [DeepNesting.SMALL_STACK], but within the nesting limit of the IDE's syntax analysis.
         */
        const val READER_DEPTH = NestingLimit.MAX_DEPTH - DeepNesting.GUARDS_STRUCTURE_DEPTH

        /** How each platform writes the completion shortcut in its menus. */
        val COMPLETION_SHORTCUT_TEXT: Map<HostPlatform, String> = mapOf(
            HostPlatform.MAC_OS to "⌃M",
            HostPlatform.WINDOWS to "Ctrl+M",
            HostPlatform.LINUX to "Ctrl+M",
        )
    }
}
