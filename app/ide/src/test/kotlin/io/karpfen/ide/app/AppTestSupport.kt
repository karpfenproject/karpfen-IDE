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
package io.karpfen.ide.app

import io.karpfen.ide.app.logging.LanguageToolsLogRouting
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.foundation.command.Command
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.files.ProjectFileOperations
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.output.OutputBuffer
import io.karpfen.ide.ui.workbench.output.OutputSink
import io.karpfen.ide.ui.workbench.output.OutputState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import io.karpfen.ide.ui.workbench.settings.ZoomStep
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import io.karpfen.ide.ui.workbench.state.WorkbenchState
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import io.karpfen.ide.ui.workbench.templates.TemplateContext
import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A scripted [WorkbenchState], so the app tests check the composition root on its own. It records the calls of the
 * composition root and registers one command. A test decides how long `openFolder` takes and whether it asks a
 * dialog, whether `start` fails, how `requestExit` answers and how long `shutdownRuntime` takes. The composition root
 * never touches the sub-states outside the window, so the fake fails on access instead of constructing the workbench's
 * sub-states.
 */
internal class FakeWorkbenchState(val scope: CoroutineScope, val services: WorkbenchServices) : WorkbenchState {
    /** The calls of the composition root, in order. A call of `openFolder` is recorded with its path. */
    val calls = mutableListOf<String>()

    /** The registry the commands were registered in, or null before `registerCommands`. */
    var registry: CommandRegistry? = null

    /** How long the `openFolder` job takes on the test's virtual time. */
    var openFolderTime: Duration = Duration.ZERO

    /** The folder the last `openFolder` job finished opening, or null. */
    var openedFolder: Path? = null

    /** When set, `start` throws it. */
    var startFailure: Exception? = null

    /** When set, `openFolder` asks this dialog and waits for the answer before the folder counts as opened. */
    var openFolderAsks: DialogRequest<*>? = null

    /** `requestExit` waits for this answer. */
    var exitAnswer = CompletableDeferred(true)

    /** How long `shutdownRuntime` takes on the test's virtual time. */
    var shutdownTime: Duration = Duration.ZERO

    override val workspace: StateFlow<Workspace?> = MutableStateFlow(null)
    override val languageServices: StateFlow<LanguageServices?> = MutableStateFlow(null)
    override val projectTree: ProjectTreeState get() = unused("projectTree")
    override val tabs: EditorTabsState get() = unused("tabs")
    override val outline: OutlineState get() = unused("outline")
    override val problems: ProblemsState get() = unused("problems")
    override val statusBar: StatusBarState get() = unused("statusBar")
    override val output: OutputState get() = unused("output")
    override val dialogs: DialogState = DialogState()
    override val layout: LayoutState get() = unused("layout")
    override val settings: StateFlow<IdeSettings> = MutableStateFlow(IdeSettings())
    override val windowTitle: StateFlow<String> = MutableStateFlow("karpfen IDE")
    override val runtime: StateFlow<RuntimeState?> = MutableStateFlow(null)

    override suspend fun start() {
        calls += "start"
        startFailure?.let { throw it }
    }

    override fun openFolder(path: Path): Job {
        calls += "openFolder $path"
        return scope.launch {
            delay(openFolderTime)
            openFolderAsks?.let { dialogs.ask(it) }
            openedFolder = path
        }
    }

    override fun registerCommands(registry: CommandRegistry) {
        calls += "registerCommands"
        this.registry = registry
        registry.register(Command(FAKE_COMMAND_ID, "Fake") {})
    }

    override suspend fun requestExit(): Boolean {
        calls += "requestExit"
        return exitAnswer.await()
    }

    override suspend fun shutdownRuntime() {
        calls += "shutdownRuntime"
        delay(shutdownTime)
    }

    override fun close() {
        calls += "close"
    }

    override fun chooseAndOpenFolder(): Job = unexpected("chooseAndOpenFolder")
    override fun openFile(path: Path): Job = unexpected("openFile")
    override fun navigateTo(location: Location): Job = unexpected("navigateTo")
    override fun openNode(path: Path): Job = unexpected("openNode")
    override fun runTreeCommand(command: ProjectTreeCommand): Job = unexpected("runTreeCommand")
    override fun createFile(directory: Path, kind: FileTemplateKind): Job = unexpected("createFile")
    override fun rename(path: Path): Job = unexpected("rename")
    override fun delete(path: Path): Job = unexpected("delete")
    override fun revealInFileManager(path: Path): Job = unexpected("revealInFileManager")
    override fun resolveConflict(path: Path, resolution: ConflictResolution): Job = unexpected("resolveConflict")
    override fun saveActive(): Job = unexpected("saveActive")
    override fun saveAll(): Job = unexpected("saveAll")
    override fun closeTab(path: Path): Job = unexpected("closeTab")
    override fun closeActiveTab(): Job = unexpected("closeActiveTab")

    override fun zoomUi(step: ZoomStep): Job = unexpected("zoomUi")

    override fun zoomEditor(step: ZoomStep): Job = unexpected("zoomEditor")

    /** Fails the test: the composition root calls no other [intent]. */
    private fun unexpected(intent: String): Nothing = throw AssertionError("the composition root does not call $intent")

    /** Fails the test: the composition root reads no [subState] outside the window. */
    private fun unused(subState: String): Nothing = throw AssertionError("the composition root does not use $subState")

    companion object {
        /** The id of the one command the fake registers. */
        const val FAKE_COMMAND_ID = "fake.command"
    }
}

/**
 * A services factory that counts its calls and builds [WorkbenchServices] of fakes for [platform], over the output of
 * the language tools that the composition root passes. The keymap's platform is the services' platform. The
 * composition root uses none of the other collaborators.
 */
internal class RecordingServicesFactory(
    private val platform: HostPlatform,
    private val ioDispatcher: CoroutineDispatcher,
) : (OutputBuffer) -> WorkbenchServices {
    /** How often the services were created. */
    var calls = 0
        private set

    /** The services the last call returned, over the output of the language tools it was given. */
    lateinit var services: WorkbenchServices
        private set

    override fun invoke(languageToolsOutput: OutputBuffer): WorkbenchServices {
        calls++
        services = WorkbenchServices(
            settings = UnusedSettings,
            workspaceFactory = { _, _ -> throw AssertionError("no workspace is opened by the fake state") },
            languageServicesFactory = { throw AssertionError("no language services are created") },
            editorStateFactory = { _, _, _, _, _ -> throw AssertionError("no editor is created") },
            fileOperations = UnusedFileOperations,
            folderChooser = { _, _ -> null },
            templates = UnusedTemplates,
            editorKeymap = defaultEditorKeymap(platform),
            ioDispatcher = ioDispatcher,
            languageToolsOutput = languageToolsOutput,
        )
        return services
    }

    /** Settings the composition root never loads or writes. The workbench state does that. */
    private object UnusedSettings : SettingsRepository {
        override val settings: StateFlow<IdeSettings> = MutableStateFlow(IdeSettings())
        override suspend fun load(): IdeSettings = throw AssertionError("settings are loaded by the workbench state")
        override suspend fun openProject(root: Path?): IdeSettings = throw AssertionError("unused")
        override suspend fun awaitWrites(): Unit = throw AssertionError("unused")
        override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings = throw AssertionError("unused")
    }

    /** File operations the composition root never runs. */
    private object UnusedFileOperations : ProjectFileOperations {
        override val supportsTrash: Boolean = false
        override val supportsRevealInFileManager: Boolean = false
        override val supportsOpenExternally: Boolean = false
        override fun createFile(directory: Path, fileName: String, content: String): Path = throw AssertionError("unused")
        override fun rename(path: Path, newName: String): Path = throw AssertionError("unused")
        override fun delete(path: Path): DeleteOutcome = throw AssertionError("unused")
        override fun revealInFileManager(path: Path) = throw AssertionError("unused")
        override fun openExternally(path: Path) = throw AssertionError("unused")
    }

    /** Templates the composition root never uses. */
    private object UnusedTemplates : FileTemplates {
        override fun fileName(kind: FileTemplateKind, baseName: String): String = throw AssertionError("unused")
        override fun content(kind: FileTemplateKind, baseName: String, context: TemplateContext): String =
            throw AssertionError("unused")
    }
}

/**
 * A [LanguageToolsLogRouting] that records the sinks it is attached to and how many of its routes were closed, so the
 * app tests check how the composition root routes what dsl-tools logs without a logging backend.
 */
internal class RecordingLanguageToolsLog : LanguageToolsLogRouting {
    /** The sink of every attach, in order. */
    val sinks: MutableList<OutputSink> = mutableListOf()

    /** How many of the returned routes were closed. */
    var closed: Int = 0
        private set

    override fun attach(sink: OutputSink): AutoCloseable {
        sinks += sink
        return AutoCloseable { closed++ }
    }
}
