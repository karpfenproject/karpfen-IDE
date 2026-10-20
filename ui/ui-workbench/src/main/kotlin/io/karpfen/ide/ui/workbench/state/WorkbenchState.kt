package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.settings.WindowBounds
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
 * sub-states' UI state and call the intents below; cross-cutting flows (open folder, navigate, close with unsaved
 * changes, exit) live here.
 *
 * Used from the UI thread; long-running work runs in the scope given to the factory.
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

    /** Opens [path] in an editor tab (or activates its tab); errors become an error dialog. */
    public suspend fun openFile(path: Path)

    /** Opens the file of [location] and moves the caret to its range start, centred (spec §8, navigateTo). */
    public suspend fun navigateTo(location: Location)

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
     * and the editor commands (`EditorActionIds` forwarded to the active editor) in [registry]; keeps enablement and
     * the recent-folder commands up to date.
     */
    public fun registerCommands(registry: CommandRegistry)

    /** Closes the workspace and cancels background work (no unsaved-changes check; see [requestExit]). */
    override fun close()
}

/** Creates the workbench state; all work runs in [scope]. */
public fun WorkbenchState(scope: CoroutineScope, services: WorkbenchServices): WorkbenchState =
    DefaultWorkbenchState(scope, services)
