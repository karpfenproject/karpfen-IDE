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
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.nio.file.Path

/**
 * Default [WorkbenchState]: creates the sub-states and coordinates them (Mediator). A coordinator only (no god class,
 * at most about 300 lines): the flows are internal collaborators of this package with one responsibility each, e.g. an
 * intent runner (launches each intent's flow in [scope], one at a time in call order, and returns its `Job`; the flows
 * themselves are internal suspend functions the tests may also call), a command registrar (commands, enablement incl.
 * the focus rule, recent folders, tool-window toggles, editor commands with `EditorActionTitles` and
 * `WorkbenchServices.editorKeymap`), a layout persister (writes `layout.state` into the settings debounced, about
 * 500 ms, and on exit; restores it in [start]), an editor-navigation collector (each tab's `navigationRequests` from
 * open to close → [navigateTo]), an unsaved-changes flow (tab close, folder switch, rename, exit), a file-operations
 * flow (create with a `TemplateContext`, rename incl. directories with reopening, delete, reveal in the file manager on
 * `WorkbenchServices.ioDispatcher`) and a template-context builder (from `Workspace.contexts` and the syntax indexes of
 * `Workspace.analysis`). Collaborators use editors as `EditorHandle`.
 *
 * The collaborators: [IntentRunner], [WorkspaceHolder], [SubStateBindings], [EditorNavigationCollector],
 * [EditorLifecycle], [SaveFlow], [UnsavedChangesFlow], [FileOpenFlow], [NavigationFlow], [TabFlows], [FolderFlow],
 * [FileOperationFlows] (with [TemplateContextBuilder]), [RenameFlow], [LayoutPersister], the [RuntimeHolder] with its
 * [WorkbenchRunSupport], and [WorkbenchCommands], [RuntimeCommands] and [EditorCommands] registering through a
 * [ModalCommandRegistry]. All work runs in a supervisor child of [scope], cancelled by [close].
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
    private val folders = FolderFlow(
        ownScope, holder, tabs, lifecycle, unsaved, bindings, runtimes, services.workspaceFactory, services.languageServicesFactory,
        services.settings, services.folderChooser, dialogs, services.ioDispatcher,
    )
    private val fileOperations = FileOperationFlows(
        holder, tabs, lifecycle, fileOpen, projectTree, dialogs, services.fileOperations, services.templates, services.ioDispatcher,
    )
    private val renames = RenameFlow(holder, tabs, lifecycle, unsaved, projectTree, dialogs, services.fileOperations, services.ioDispatcher)
    private val persister = LayoutPersister(ownScope, layout, services.settings)

    init {
        bindings.start()
    }

    override suspend fun start() {
        layout.restore(services.settings.load())
        persister.start()
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

    override suspend fun requestExit(): Boolean = runner.exclusive {
        unsaved.confirm(tabs.documents()).also { mayExit -> if (mayExit) persister.persistNow() }
    }

    override fun registerCommands(registry: CommandRegistry) {
        val modal = ModalCommandRegistry(ownScope, registry, dialogs.current)
        WorkbenchCommands(ownScope, this, tabs, layout, services.settings).register(modal)
        RuntimeCommands(ownScope, runtimes.state).register(modal)
        EditorCommands(ownScope, tabs.activeEditor, services.editorKeymap).register(modal)
    }

    override fun close() {
        folders.closeWorkspace()
        ownScope.cancel()
    }

    /** The absolute, normalized form of this path, the one form in which the flows and sub-states compare paths. */
    private fun Path.normalized(): Path = toAbsolutePath().normalize()

    /** The [IntentRunner.launchOnce] key of closing the tab of [path]. */
    private data class CloseTab(val path: Path)

    private companion object {
        /** The job of an intent with nothing to do. */
        val NOTHING_TO_DO: Job = Job().apply { complete() }
    }
}
