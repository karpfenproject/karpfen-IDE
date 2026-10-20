package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
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
 * The workbench coordinator (Mediator, spec §8): owns the current [Workspace] and its [LanguageServices] and
 * coordinates the cohesive sub-states, each a plain Compose-free class in its feature package with its own tests.
 * Composables render the sub-states' UI state and call the intents below and the sub-states' public UI intents;
 * the sub-states' coordination operations (binding, opening and removing tabs, setting the tree) are internal to the
 * module, so only the mediator reaches them (decision 31 of the work-package plan). Cross-cutting flows (open folder,
 * open/navigate, file operations of the project tree, external-change conflicts, close with unsaved changes, exit) live
 * here, because they span several sub-states, the [Workspace] and the dialogs.
 *
 * The mediator collects every editor's `CodeEditorState.navigationRequests` from opening its tab until closing it and
 * runs [navigateTo] for each, so navigation never depends on a composable being on screen.
 *
 * Used from the UI thread; long-running work runs in the scope given to the factory, blocking file work on
 * `WorkbenchServices.ioDispatcher`.
 */
public interface WorkbenchState : AutoCloseable {

    /** The opened folder's workspace, or null (empty state with "Open Folder…" and recent folders). */
    public val workspace: StateFlow<Workspace?>

    /** Language services of the current workspace, or null. */
    public val languageServices: StateFlow<LanguageServices?>

    /** Project tree. */
    public val projectTree: ProjectTreeState

    /** Open editor tabs and their editor states. */
    public val tabs: EditorTabsState

    /** Outline of the active document. */
    public val outline: OutlineState

    /** Problems of the workspace. */
    public val problems: ProblemsState

    /** Status bar. */
    public val statusBar: StatusBarState

    /** Pending dialog (unsaved changes, delete confirmation, rename, new file, errors). */
    public val dialogs: DialogState

    /**
     * Tool-window visibility and sizes, theme and window bounds as live UI state; persisted into [settings] by the
     * mediator (debounced, and in [requestExit]).
     */
    public val layout: LayoutState

    /** The persisted IDE settings (recent folders, font size; the layout as last persisted). */
    public val settings: StateFlow<IdeSettings>

    /** `<folder> — karpfen IDE`, or `karpfen IDE` without a folder (spec §9). */
    public val windowTitle: StateFlow<String>

    /** Loads the settings and restores the [layout]; call once before showing the window. */
    public suspend fun start()

    /**
     * Opens [path] as the workspace (after the unsaved-changes flow for the current one; no-op if cancelled) and records
     * it as the most recent folder.
     */
    public suspend fun openFolder(path: Path)

    /** Shows the folder chooser and opens the chosen folder. */
    public suspend fun chooseAndOpenFolder()

    /**
     * Opens [path] in an editor tab (or activates its tab). Files the editor cannot open (`OpenResult.Unsupported`, e.g.
     * `*.kenv.json`) are opened in the system's default application (`ProjectFileOperations.openExternally`, decision 14
     * of the work-package plan); failures become an error dialog.
     */
    public suspend fun openFile(path: Path)

    /**
     * Opens the file of [location] and moves the caret to its range start, centred (spec §8, navigateTo): the range is
     * mapped into the document's current text with `EditableText.mapOffset` when [Location.lineage] is the text's
     * lineage and [Location.version] is not newer than the current version (and the history still has it), otherwise
     * clamped. The caret move, `scrollTo(center = true)` and the focus request are pending state of the editor
     * (`CodeEditorState.scrollRequest`/`focusRequested`), so they take effect when a new tab is composed. Files the editor
     * cannot open are opened externally (Problems navigation to descriptor diagnostics). The Problems and Outline views
     * call it with `ProblemItem.location` and `OutlineUiState.locationOf(node)`.
     */
    public suspend fun navigateTo(location: Location)

    /**
     * New file from a template (project-tree context menu, spec §8): asks for the name ([DialogRequest.NewFile]),
     * creates `FileTemplates.fileName(kind, name)` with `FileTemplates.content(kind, name, templateContext)` in
     * [directory] through `ProjectFileOperations.createFile` (the `TemplateContext` built from the directory's current
     * context and the latest analysis), refreshes the workspace, reveals the new file in the project tree and opens it
     * ([openFile]). Cancel does nothing; failures (e.g. the file exists) become an error dialog.
     */
    public suspend fun createFile(directory: Path, kind: FileTemplateKind)

    /**
     * Renames [path] (project-tree context menu): asks for the new name ([DialogRequest.Rename]); if the file is open
     * and dirty, runs the unsaved-changes dialog first (Save / Discard / Cancel); closes its document, renames through
     * `ProjectFileOperations.rename`, refreshes the workspace and, if it was open, reopens the renamed file in the same
     * tab position. Failures become an error dialog (the document is reopened under the old name).
     */
    public suspend fun rename(path: Path)

    /**
     * Deletes [path] (project-tree context menu) after confirmation ([DialogRequest.ConfirmDelete], to the trash when
     * `ProjectFileOperations.supportsTrash`): closes the open documents of the file (or below the directory) without
     * saving, deletes through `ProjectFileOperations.delete` and refreshes the workspace. Failures become an error
     * dialog.
     */
    public suspend fun delete(path: Path)

    /** Shows [path] in Finder/Explorer (`ProjectFileOperations.reveal`); no-op where unsupported. */
    public suspend fun revealInFileManager(path: Path)

    /**
     * Resolves the external-change conflict of the open document [path] (the editor area's banner, Reload / Keep mine;
     * spec §4, §8) through `OpenDocument.resolveConflict`. For a file deleted on disk the banner offers Close / Keep
     * mine and RELOAD closes the tab without saving. No-op without a conflict.
     */
    public suspend fun resolveConflict(path: Path, resolution: ConflictResolution)

    /** Saves the active document. */
    public suspend fun saveActive()

    /** Saves all dirty documents. */
    public suspend fun saveAll()

    /** Closes the tab of [path], asking about unsaved changes; returns false if the user cancelled. */
    public suspend fun closeTab(path: Path): Boolean

    /** Closes the active tab (see [closeTab]). */
    public suspend fun closeActiveTab(): Boolean

    /**
     * Runs the unsaved-changes flow over every dirty document and persists the settings (with the current [layout]);
     * true if the app may exit.
     */
    public suspend fun requestExit(): Boolean

    /**
     * Registers the workbench commands (`WorkbenchCommandIds`, including one "Open Recent" command per recent folder;
     * the View commands drive [layout]) and the editor commands in [registry]; keeps enablement and the recent-folder
     * commands up to date.
     *
     * Editor commands are registered under their `EditorActionIds` with their `EditorActionTitles` (the titles the
     * editor context menu shows too), forwarded to the active editor (`CodeEditorState.execute(id)`), enabled while the
     * active editor's `enabledActions` contains the id, and carry `shortcut = WorkbenchServices.editorKeymap
     * .shortcutFor(id)` (the keymap every editor is created with, the single source of their shortcuts). Key routing
     * (decision 15 of the work-package plan): the focused component handles and consumes its keys first (the editor
     * through `CodeEditorState.handleKey`, text fields natively), so a menu accelerator only fires for strokes nobody
     * consumed; one key press runs an action once.
     */
    public fun registerCommands(registry: CommandRegistry)

    /** Closes the workspace and cancels background work (no unsaved-changes check; see [requestExit]). */
    override fun close()
}

/** Creates the workbench state; all work runs in [scope]. */
public fun WorkbenchState(scope: CoroutineScope, services: WorkbenchServices): WorkbenchState =
    DefaultWorkbenchState(scope, services)
