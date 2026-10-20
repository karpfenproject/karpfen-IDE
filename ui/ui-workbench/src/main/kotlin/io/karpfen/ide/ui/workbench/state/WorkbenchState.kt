package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.settings.WindowBounds
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** The tool windows around the editor area (spec §8). */
public enum class ToolWindowId {
    /** Project tree (left). */
    PROJECT,

    /** Outline of the active document (right). */
    OUTLINE,

    /** Problems (bottom). */
    PROBLEMS,
}

/**
 * The workbench coordinator (Mediator, spec §8): owns the current [Workspace] and its [LanguageServices] and
 * coordinates the cohesive sub-states, each a plain Compose-free class with its own tests. Composables render the
 * sub-states' UI state and call the intents below; cross-cutting flows (open folder, open/navigate, file operations of
 * the project tree, external-change conflicts, close with unsaved changes, exit) live here, because they span several
 * sub-states, the [Workspace] and the dialogs.
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

    /** The IDE settings (recent folders, theme, font size, window bounds, tool-window layout). */
    public val settings: StateFlow<IdeSettings>

    /** `<folder> — karpfen IDE`, or `karpfen IDE` without a folder (spec §9). */
    public val windowTitle: StateFlow<String>

    /** Loads the settings; call once before showing the window. */
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
     * Opens the file of [location] and moves the caret to its range start, centred (spec §8, navigateTo): a versioned
     * range ([Location.version]) is mapped into the document's current text with `EditableText.mapOffset` when
     * possible, otherwise clamped. The caret move, `scrollTo(center = true)` and the focus request are pending state of
     * the editor (`CodeEditorState.scrollRequest`/`focusRequested`), so they take effect when a new tab is composed.
     * Files the editor cannot open are opened externally (Problems navigation to descriptor diagnostics).
     */
    public suspend fun navigateTo(location: Location)

    /**
     * New file from a template (project-tree context menu, spec §8): asks for the name ([DialogRequest.NewFile]),
     * creates `FileTemplates.fileName(kind, name)` with `FileTemplates.content(kind, name)` in [directory] through
     * `ProjectFileOperations.createFile`, refreshes the workspace, reveals the new file in the project tree and opens it
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
    public suspend fun reveal(path: Path)

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

    /** Runs the unsaved-changes flow over every dirty document and persists the settings; true if the app may exit. */
    public suspend fun requestExit(): Boolean

    /** Shows or hides a tool window. */
    public fun toggleToolWindow(id: ToolWindowId)

    /** Sets a tool window's size in dp (width for PROJECT/OUTLINE, height for PROBLEMS). */
    public fun resizeToolWindow(id: ToolWindowId, size: Float)

    /** Switches the theme. */
    public fun setTheme(mode: ThemeMode)

    /** Records the main window's bounds (persisted with the settings). */
    public fun updateWindowBounds(bounds: WindowBounds)

    /**
     * Registers the workbench commands (`WorkbenchCommandIds`, including one "Open Recent" command per recent folder)
     * and the editor commands in [registry]; keeps enablement and the recent-folder commands up to date.
     *
     * Editor commands are registered under their `EditorActionIds`, forwarded to the active editor
     * (`CodeEditorState.execute(id)`), enabled while the active editor's `enabledActions` contains the id, and carry
     * `shortcut = defaultEditorKeymap(platform).shortcutFor(id)` (the editor keymap is the single source of their
     * shortcuts). Key routing (decision 15 of the work-package plan): the focused component handles and consumes its
     * keys first (the editor through its keymap, text fields natively), so a menu accelerator only fires for strokes
     * nobody consumed; one key press runs an action once.
     */
    public fun registerCommands(registry: CommandRegistry)

    /** Closes the workspace and cancels background work (no unsaved-changes check; see [requestExit]). */
    override fun close()
}

/** Creates the workbench state; all work runs in [scope]. */
public fun WorkbenchState(scope: CoroutineScope, services: WorkbenchServices): WorkbenchState =
    DefaultWorkbenchState(scope, services)
