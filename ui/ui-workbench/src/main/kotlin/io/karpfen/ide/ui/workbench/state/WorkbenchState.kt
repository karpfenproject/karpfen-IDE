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
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * The workbench coordinator (Mediator): owns the current [Workspace] and its [LanguageServices] and coordinates the
 * cohesive sub-states, each a plain Compose-free class in its feature package with its own tests. Composables render
 * the sub-states' UI state and call the intents below and the sub-states' public UI intents; the sub-states'
 * coordination operations (binding, opening and removing tabs, setting the tree) are internal to the module, so only
 * the mediator reaches them and no view can bypass a coordinated flow (e.g. close a tab without the unsaved-changes
 * dialog). Cross-cutting flows (open folder, open/navigate, file operations of the project tree, external-change
 * conflicts, close with unsaved changes, exit) live here, because they span several sub-states, the [Workspace] and the
 * dialogs.
 *
 * Intents own their lifetime: the UI intents are plain functions that launch their flow in the mediator's scope and
 * return its [Job] (tests `join()` it), so a flow survives the composable that started it (a tree row disappearing
 * after a rename, a tab closing) and views never launch coroutines for intents. Flows run one at a time in call order
 * (a later intent waits until the earlier flow finished), and a repeated close request for a tab returns the running
 * one (a second click on a tab's close button or a second Close Tab cannot open a second unsaved-changes dialog,
 * whatever the first one is answered); [registerCommands]' commands call the same intents. Only [start] and
 * [requestExit], called by the composition root, suspend.
 *
 * Saving never silently writes over a change made on disk: Save, Save All and Save in the unsaved-changes dialog
 * (tab close, folder switch, rename, exit) ask before overwriting a document whose external-change conflict is
 * unresolved ([DialogRequest.ConfirmOverwrite]; Cancel keeps the disk version and the conflict, activates that tab so
 * its banner shows, and stops a close or exit). The editor area marks tabs in conflict on the tab strip too.
 *
 * The mediator collects every editor's `EditorHandle.navigationRequests` from opening its tab until closing it and
 * runs [navigateTo] for each, so navigation never depends on a composable being on screen. Its collaborators and the
 * sub-states use editors through the `EditorHandle` role; only the editor area renders the full `CodeEditorState`.
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

    /** `<folder> — karpfen IDE`, or `karpfen IDE` without a folder. */
    public val windowTitle: StateFlow<String>

    /** Loads the settings and restores the [layout]; call once before showing the window. */
    public suspend fun start()

    /**
     * Opens [path] as the workspace (after the unsaved-changes flow for the current one; no-op if cancelled) and records
     * it as the most recent folder. A path that is not a directory (e.g. a recent folder deleted or unmounted since)
     * changes nothing but the recent folders, which lose it, and shows an error dialog.
     */
    public fun openFolder(path: Path): Job

    /** Shows the folder chooser and opens the chosen folder. */
    public fun chooseAndOpenFolder(): Job

    /**
     * Opens [path] in an editor tab (or activates its tab): KMeta, KModel and KStates files and environment
     * descriptors. Files the editor cannot open (`OpenResult.Unsupported`, e.g. a bridge configuration) are opened in
     * the system's default application (`ProjectFileOperations.openExternally`), also when navigating to them from the
     * Problems view; failures become an error dialog.
     */
    public fun openFile(path: Path): Job

    /**
     * Opens the file of [location] and moves the caret to its range start, centred: the range is mapped into the
     * document's current text with `EditableText.mapRange(range, version, lineage)` and clamped to the text when that
     * returns null (another lineage, a newer version, evicted history, or no version at all). The caret move,
     * `scrollTo(center = true)` and the focus request are pending state of the editor, so they take effect when a new
     * tab is composed. Files the editor cannot open are opened externally. The Problems and Outline views call it
     * with `ProblemItem.location` and `OutlineUiState.locationOf(node)`.
     */
    public fun navigateTo(location: Location): Job

    /**
     * Opens the project-tree node [path] (double-click, Enter): a directory of the tree is expanded or collapsed at once
     * (`ProjectTreeState.toggle`; the returned job is complete), anything else is opened with [openFile].
     */
    public fun openNode(path: Path): Job

    /**
     * Runs [command], chosen in the project tree's context menu (`ProjectTreeState.menuFor`), through its intent:
     * [createFile], [rename], [delete] or [revealInFileManager].
     */
    public fun runTreeCommand(command: ProjectTreeCommand): Job

    /**
     * New file from a template (project-tree context menu): asks for the name ([DialogRequest.NewFile]), creates
     * `FileTemplates.fileName(kind, name)` with `FileTemplates.content(kind, name, templateContext)` in [directory]
     * through `ProjectFileOperations.createFile` (the `TemplateContext` built as documented on it, from the directory's
     * current context and the syntax indexes of the latest analysis), refreshes the workspace, reveals the new file in
     * the project tree and opens it ([openFile]). Cancel does nothing; failures (e.g. the file exists) become an error
     * dialog.
     */
    public fun createFile(directory: Path, kind: FileTemplateKind): Job

    /**
     * Renames [path], a file or a directory (project-tree context menu): asks for the new name ([DialogRequest.Rename]);
     * runs the unsaved-changes dialog over the dirty documents among the open ones affected — the file, or every open
     * document below the directory — (Save / Discard / Cancel; Cancel aborts); closes those documents, renames through
     * `ProjectFileOperations.rename`, refreshes the workspace and reopens each of them under its new path in its former
     * tab position (the active tab stays active). Failures become an error dialog, and the documents are reopened under
     * their old paths.
     */
    public fun rename(path: Path): Job

    /**
     * Deletes [path] (project-tree context menu) after confirmation ([DialogRequest.ConfirmDelete], to the trash when
     * `ProjectFileOperations.supportsTrash`): closes the open documents of the file (or below the directory) without
     * saving, deletes through `ProjectFileOperations.delete` and refreshes the workspace. Failures become an error
     * dialog.
     */
    public fun delete(path: Path): Job

    /** Shows [path] in Finder/Explorer (`ProjectFileOperations.revealInFileManager`); no-op where unsupported. */
    public fun revealInFileManager(path: Path): Job

    /**
     * Resolves the external-change conflict of the open document [path] (the editor area's banner, chosen from
     * `EditorTab.conflict`) through `OpenDocument.resolveConflict`. For a file changed on disk the banner offers Reload
     * ([ConflictResolution.RELOAD]) / Keep mine; for a file deleted on disk it offers Close
     * ([ConflictResolution.RELOAD], answered with `ConflictResult.DeletedOnDisk`, on which the mediator closes the tab
     * without saving) / Keep mine. `ConflictResult.Failed` becomes an error dialog. No-op without a conflict.
     */
    public fun resolveConflict(path: Path, resolution: ConflictResolution): Job

    /** Saves the active document (asking first if that overwrites an external change, see above). */
    public fun saveActive(): Job

    /** Saves all dirty documents (asking first about those whose save overwrites an external change, see above). */
    public fun saveAll(): Job

    /**
     * Closes the tab of [path], asking about unsaved changes (Cancel keeps it open). While a close request of the tab is
     * queued or asking, a further one returns its job instead of asking again.
     */
    public fun closeTab(path: Path): Job

    /** Closes the tab that is active when called (see [closeTab]). */
    public fun closeActiveTab(): Job

    /**
     * Runs the unsaved-changes flow over every dirty document and persists the settings (with the current [layout]);
     * true if the app may exit. Waits for a running intent flow first.
     */
    public suspend fun requestExit(): Boolean

    /**
     * Registers the workbench commands (`WorkbenchCommandIds`, including one "Open Recent" command per recent folder
     * and one toggle per `ToolWindowId`; the View commands drive [layout]) and the editor commands in [registry]; keeps
     * enablement and the recent-folder commands up to date. Commands call the non-suspending intents above. The
     * [dialogs] are modal: while one is shown, every command is disabled and does nothing when run, so a shortcut typed
     * into a dialog never acts on the workbench behind it.
     *
     * Editor commands are registered under their `EditorActionIds` with their `EditorActionTitles` (the titles the
     * editor context menu shows too), forwarded to the active editor (`EditorHandle.execute(id)`), and carry
     * `shortcut = WorkbenchServices.editorKeymap.shortcutFor(id)` (the keymap every editor is created with, the single
     * source of their shortcuts). Enablement: the id is in the active editor's `enabledActions`, and — for every editor
     * command except FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, GO_TO_LINE and GO_TO_DEFINITION, which open a popup or
     * navigate and first request focus for the editor (FIND_NEXT and FIND_PREVIOUS only while its find bar is closed:
     * typed into the bar, they keep the focus there) — the active editor `isFocused`. So Cut, Paste, Undo, Duplicate,
     * Toggle Comment … from the menu never act on an editor while the Project tree, Outline, Problems or a text field
     * has focus. Key routing: the focused component handles and consumes its keys first (the editor through
     * `CodeEditorState.handleKey`, text fields natively), so a menu accelerator only fires for strokes nobody consumed;
     * one key press runs an action once.
     */
    public fun registerCommands(registry: CommandRegistry)

    /** Closes the workspace and cancels background work (no unsaved-changes check; see [requestExit]). */
    override fun close()
}

/** Creates the workbench state; all work runs in [scope]. */
public fun WorkbenchState(scope: CoroutineScope, services: WorkbenchServices): WorkbenchState =
    DefaultWorkbenchState(scope, services)
