package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Default [WorkbenchState]: creates the sub-states and coordinates them (Mediator). A coordinator only (spec §1a, no
 * god class, ≤ ~300 lines): the flows are internal collaborators of this package with one responsibility each, e.g. a
 * command registrar (commands, enablement, recent folders, editor commands with `EditorActionTitles` and
 * `WorkbenchServices.editorKeymap`), a layout persister (writes `layout.state` into the settings debounced, about
 * 500 ms, and on exit; restores it in [start]), an editor-navigation collector (each tab's `navigationRequests` from
 * open to close → [navigateTo]), an unsaved-changes flow (tab close, folder switch, exit) and a file-operations flow
 * (create with a `TemplateContext`, rename, delete, reveal on `WorkbenchServices.ioDispatcher`).
 */
internal class DefaultWorkbenchState(
    private val scope: CoroutineScope,
    private val services: WorkbenchServices,
) : WorkbenchState {

    override val workspace: StateFlow<Workspace?> get() = TODO("WP-WORKBENCH: workspace")
    override val languageServices: StateFlow<LanguageServices?> get() = TODO("WP-WORKBENCH: language services")
    override val projectTree: ProjectTreeState get() = TODO("WP-WORKBENCH: project tree state")
    override val tabs: EditorTabsState get() = TODO("WP-WORKBENCH: tabs state")
    override val outline: OutlineState get() = TODO("WP-WORKBENCH: outline state")
    override val problems: ProblemsState get() = TODO("WP-WORKBENCH: problems state")
    override val statusBar: StatusBarState get() = TODO("WP-WORKBENCH: status bar state")
    override val dialogs: DialogState get() = TODO("WP-WORKBENCH: dialog state")
    override val layout: LayoutState get() = TODO("WP-WORKBENCH: layout state")
    override val settings: StateFlow<IdeSettings> get() = TODO("WP-WORKBENCH: settings")
    override val windowTitle: StateFlow<String> get() = TODO("WP-WORKBENCH: window title")

    override suspend fun start(): Unit = TODO("WP-WORKBENCH: load settings, restore the layout")

    override suspend fun openFolder(path: Path): Unit = TODO("WP-WORKBENCH: open folder")

    override suspend fun chooseAndOpenFolder(): Unit = TODO("WP-WORKBENCH: choose folder")

    override suspend fun openFile(path: Path): Unit = TODO("WP-WORKBENCH: open file")

    override suspend fun navigateTo(location: Location): Unit = TODO("WP-WORKBENCH: navigate")

    override suspend fun createFile(directory: Path, kind: FileTemplateKind): Unit = TODO("WP-WORKBENCH: new file flow")

    override suspend fun rename(path: Path): Unit = TODO("WP-WORKBENCH: rename flow")

    override suspend fun delete(path: Path): Unit = TODO("WP-WORKBENCH: delete flow")

    override suspend fun revealInFileManager(path: Path): Unit = TODO("WP-WORKBENCH: reveal in Finder/Explorer")

    override suspend fun resolveConflict(path: Path, resolution: ConflictResolution): Unit =
        TODO("WP-WORKBENCH: resolve external-change conflict")

    override suspend fun saveActive(): Unit = TODO("WP-WORKBENCH: save active")

    override suspend fun saveAll(): Unit = TODO("WP-WORKBENCH: save all")

    override suspend fun closeTab(path: Path): Boolean = TODO("WP-WORKBENCH: close tab")

    override suspend fun closeActiveTab(): Boolean = TODO("WP-WORKBENCH: close active tab")

    override suspend fun requestExit(): Boolean = TODO("WP-WORKBENCH: exit flow")

    override fun registerCommands(registry: CommandRegistry): Unit = TODO("WP-WORKBENCH: commands")

    override fun close(): Unit = TODO("WP-WORKBENCH: close")
}
