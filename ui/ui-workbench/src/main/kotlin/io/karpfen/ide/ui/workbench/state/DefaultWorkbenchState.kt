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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.output.OutputState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeMenu
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.Zoom
import io.karpfen.ide.ui.workbench.settings.ZoomStep
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The default [WorkbenchState]. It creates the sub-states and coordinates them, following the Mediator pattern.
 *
 * The class is a coordinator only. It must not grow into a god class and stays at about 300 lines at most. Each flow is
 * an internal collaborator of this package with a single responsibility. The flows are internal suspend functions,
 * which the tests may also call directly. The collaborators are these:
 *
 * - [IntentRunner] launches the flow of each intent in [scope], one at a time in call order, and returns its `Job`.
 * - [WorkspaceHolder] holds the open workspace, its language services and the window title.
 * - [SubStateBindings] binds the read-only sub-states to the workspace, the active editor, the layout and the runtime.
 * - [EditorNavigationCollector] collects the `navigationRequests` of each tab from open to close and hands them to
 *   [navigateTo].
 * - [EditorLifecycle] opens and closes documents together with their editors and tabs.
 * - [SaveFlow] saves documents and asks before it writes over a change made on disk.
 * - [UnsavedChangesFlow] asks about unsaved changes before a tab closes, the folder switches, a file is renamed or the
 *   IDE exits.
 * - [FileOpenFlow] and [NavigationFlow] open files and navigate to locations.
 * - [TabFlows] closes tabs, saves them and resolves conflicts with changes on disk.
 * - [FolderFlow] opens, switches and closes the workspace folder.
 * - [FileOperationFlows] creates files with a `TemplateContext` from [TemplateContextBuilder], deletes files and shows
 *   them in the file manager. The file work runs on `WorkbenchServices.ioDispatcher`. [TemplateContextBuilder] reads
 *   `Workspace.contexts` and the syntax indexes of `Workspace.analysis`.
 * - [RenameFlow] renames files and directories and reopens what was open below them.
 * - [LayoutPersister] writes `layout.state` into the settings, debounced by about 500 ms and once more on exit. The
 *   layout itself is restored in [start] and again whenever a folder is opened.
 * - [OpenFilesPersister] writes the open files into the settings of the project the same way.
 * - [ProjectFlow] switches to the settings of the project in a folder when the folder opens. These are the theme, the
 *   zoom, the layout of the tool windows, the runtime choice and the open files, all stored in the folder's
 *   `.karpfenproject`.
 * - [RuntimeHolder] owns the runtime state of the workspace and uses [WorkbenchRunSupport] to reach the workbench.
 * - [WorkbenchCommands], [RuntimeCommands] and [EditorCommands] register the commands through a
 *   [ModalCommandRegistry]. They cover enablement including the focus rule, the recent folders, the tool window
 *   toggles, and the editor commands with their `EditorActionTitles` and the `WorkbenchServices.editorKeymap`.
 *
 * The collaborators see editors only as `EditorHandle`. All work runs in a supervisor child scope of [scope], which
 * [close] cancels.
 */
internal class DefaultWorkbenchState(
    scope: CoroutineScope,
    private val services: WorkbenchServices,
) : WorkbenchState {

    private val ownScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val holder = WorkspaceHolder()

    override val projectTree: ProjectTreeState =
        ProjectTreeState(ProjectTreeMenu(services.platform) { services.fileOperations.supportsRevealInFileManager })
    override val tabs: EditorTabsState = EditorTabsState(ownScope)
    override val outline: OutlineState = OutlineState(ownScope)
    override val problems: ProblemsState = ProblemsState(ownScope)
    override val statusBar: StatusBarState = StatusBarState(ownScope)
    override val output: OutputState = OutputState(ownScope, services.languageToolsOutput)
    override val dialogs: DialogState = DialogState()
    override val layout: LayoutState = LayoutState()

    override val workspace: StateFlow<Workspace?> get() = holder.workspace
    override val languageServices: StateFlow<LanguageServices?> get() = holder.languageServices
    override val settings: StateFlow<IdeSettings> get() = services.settings.settings
    override val windowTitle: StateFlow<String> get() = holder.windowTitle
    override val runtime: StateFlow<RuntimeState?> get() = runtimes.state

    private val runner = IntentRunner(ownScope) { intent, error ->
        dialogs.showError("Unexpected error", "'$intent' failed: ${error.message ?: error}")
    }
    private val editorSettings: StateFlow<EditorSettings> = services.settings.settings
        .map { EditorSettings(fontSize = it.editorFontSize) }
        .stateIn(ownScope, SharingStarted.Eagerly, EditorSettings(fontSize = services.settings.settings.value.editorFontSize))
    private val bindings = SubStateBindings(ownScope, tabs, projectTree, outline, problems, statusBar, output, layout)
    private val navigation = EditorNavigationCollector(ownScope, ::navigateTo)
    private val lifecycle = EditorLifecycle(
        ownScope, holder, tabs, services.editorStateFactory, editorSettings, services.editorKeymap, navigation,
    )
    private val saving = SaveFlow(holder, tabs, dialogs)
    private val unsaved = UnsavedChangesFlow(saving, dialogs)
    private val fileOpen = FileOpenFlow(lifecycle, dialogs, services.fileOperations, services.ioDispatcher)
    private val navigationFlow = NavigationFlow(fileOpen, tabs)
    private val tabFlows = TabFlows(tabs, lifecycle, unsaved, saving, dialogs)
    private val runtimes = RuntimeHolder(ownScope, services, WorkbenchRunSupport(runner, tabs, saving, dialogs, layout), bindings)
    private val persister = LayoutPersister(ownScope, layout, services.settings)
    private val openFiles = OpenFilesPersister(ownScope, tabs, holder, services.settings)
    private val project = ProjectFlow(services.settings, holder, layout, persister, openFiles, tabs, lifecycle, services.ioDispatcher)
    private val folders = FolderFlow(
        ownScope, holder, tabs, lifecycle, unsaved, bindings, runtimes, project, services.workspaceFactory,
        services.languageServicesFactory, services.settings, services.folderChooser, dialogs, services.ioDispatcher,
    )
    private val fileOperations = FileOperationFlows(
        holder, tabs, lifecycle, fileOpen, projectTree, dialogs, services.fileOperations, services.templates, services.ioDispatcher,
    )
    private val renames = RenameFlow(holder, tabs, lifecycle, unsaved, projectTree, dialogs, services.fileOperations, services.ioDispatcher)

    init {
        bindings.start()
    }

    override suspend fun start() {
        layout.restore(services.settings.load())
        persister.start()
        openFiles.start()
        project.showProjectFile(ownScope)
    }

    override fun openFolder(path: Path): Job = runner.launch("open folder") { folders.openFolder(path) }

    override fun chooseAndOpenFolder(): Job = runner.launch("choose folder") { folders.chooseAndOpenFolder() }

    override fun openFile(path: Path): Job = runner.launch("open file") { fileOpen.openFile(path.normalized()) }

    override fun navigateTo(location: Location): Job = runner.launch("navigate") { navigationFlow.navigateTo(location) }

    override fun openNode(path: Path): Job {
        if (!projectTree.isDirectory(path)) return openFile(path)
        projectTree.toggle(path)
        return NOTHING_TO_DO
    }

    override fun runTreeCommand(command: ProjectTreeCommand): Job = when (command) {
        is ProjectTreeCommand.NewFile -> createFile(command.directory, command.kind)
        is ProjectTreeCommand.Rename -> rename(command.path)
        is ProjectTreeCommand.Delete -> delete(command.path)
        is ProjectTreeCommand.RevealInFileManager -> revealInFileManager(command.path)
    }

    override fun createFile(directory: Path, kind: FileTemplateKind): Job =
        runner.launch("new file") { fileOperations.createFile(directory.normalized(), kind) }

    override fun rename(path: Path): Job = runner.launch("rename") { renames.rename(path.normalized()) }

    override fun delete(path: Path): Job = runner.launch("delete") { fileOperations.delete(path.normalized()) }

    override fun revealInFileManager(path: Path): Job =
        runner.launch("reveal in file manager") { fileOperations.revealInFileManager(path.normalized()) }

    override fun resolveConflict(path: Path, resolution: ConflictResolution): Job =
        runner.launch("resolve conflict") { tabFlows.resolveConflict(path.normalized(), resolution) }

    override fun saveActive(): Job = runner.launch("save") { tabFlows.saveActive() }

    override fun saveAll(): Job = runner.launch("save all") { tabFlows.saveAll() }

    override fun closeTab(path: Path): Job {
        val tab = path.normalized()
        return runner.launchOnce(CloseTab(tab), "close tab") { tabFlows.closeTab(tab) }
    }

    override fun closeActiveTab(): Job = tabs.active.value?.let(::closeTab) ?: NOTHING_TO_DO

    override fun zoomUi(step: ZoomStep): Job =
        runner.launch("zoom") { services.settings.update { it.copy(uiScale = Zoom.uiScale(it.uiScale, step)) } }

    override fun zoomEditor(step: ZoomStep): Job = runner.launch("zoom editor") {
        services.settings.update { it.copy(editorFontSize = Zoom.editorFontSize(it.editorFontSize, step)) }
    }

    override suspend fun requestExit(): Boolean = runner.exclusive {
        unsaved.confirm(tabs.documents()).also { mayExit ->
            if (mayExit) {
                persister.persistNow()
                openFiles.persistNow()
            }
        }
    }

    override suspend fun shutdownRuntime() {
        runtimes.shutdown()
    }

    override fun registerCommands(registry: CommandRegistry) {
        val modal = ModalCommandRegistry(ownScope, registry, dialogs.current)
        WorkbenchCommands(ownScope, this, tabs, layout, services.settings).register(modal)
        RuntimeCommands(ownScope, runtimes.state, layout).register(modal)
        EditorCommands(ownScope, tabs.activeEditor, services.editorKeymap).register(modal)
    }

    override fun close() {
        openFiles.pause()
        folders.closeWorkspace()
        ownScope.cancel()
    }

    /**
     * The absolute, normalized form of this path. The flows and sub-states compare paths only in this form, so every
     * intent normalizes its path first.
     */
    private fun Path.normalized(): Path = toAbsolutePath().normalize()

    /** The [IntentRunner.launchOnce] key for closing the tab of [path]. */
    private data class CloseTab(val path: Path)

    private companion object {
        /** The job of an intent with nothing to do. */
        val NOTHING_TO_DO: Job = Job().apply { complete() }
    }
}
