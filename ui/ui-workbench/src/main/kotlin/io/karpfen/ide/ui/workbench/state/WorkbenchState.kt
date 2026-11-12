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
import io.karpfen.ide.ui.foundation.command.CommandRegistry
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.layout.LayoutState
import io.karpfen.ide.ui.workbench.outline.OutlineState
import io.karpfen.ide.ui.workbench.output.OutputState
import io.karpfen.ide.ui.workbench.problems.ProblemsState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeCommand
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.ZoomStep
import io.karpfen.ide.ui.workbench.statusbar.StatusBarState
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * The workbench coordinator, following the Mediator pattern. It owns the current [Workspace] and its
 * [LanguageServices] and coordinates the sub-states. Each sub-state is a plain class in its feature package. It does
 * not depend on Compose and has its own tests.
 *
 * Composables render the UI state of the sub-states. They call the intents below and the public UI intents of the
 * sub-states. The coordination operations of the sub-states are internal to the module. Examples are binding, opening
 * and removing tabs, and setting the tree. Only the mediator reaches them, so no view can bypass a coordinated flow.
 * A view can therefore never close a tab without the unsaved-changes dialog. The flows that span several sub-states,
 * the [Workspace] and the dialogs live here. They are open folder, open file and navigate, the file operations of the
 * project tree, conflicts with changes on disk, closing with unsaved changes, and exit.
 *
 * Intents own their lifetime. The UI intents are plain functions. They launch their flow in the mediator's scope and
 * return its [Job], which tests can `join()`. A flow therefore survives the composable that started it, such as a tree
 * row that disappears after a rename or a tab that closes. Views never launch coroutines for intents.
 *
 * Flows run one at a time in call order. A later intent waits until the earlier flow has finished. A repeated close
 * request for a tab returns the job of the running one. A second click on a tab's close button, or a second Close Tab,
 * therefore cannot open a second unsaved-changes dialog, however the first one is answered. The commands of
 * [registerCommands] call the same intents. Only [start], [requestExit] and [shutdownRuntime] suspend. The
 * composition root calls them.
 *
 * Saving never silently writes over a change made on disk. Save, Save All and Save in the unsaved-changes dialog ask
 * before they overwrite a document whose conflict with a change on disk is unresolved. They ask through
 * [DialogRequest.ConfirmOverwrite]. Cancel keeps the version on disk and the conflict. It activates that document's
 * tab, so its banner shows, and it stops a close or an exit. The editor area also marks tabs in conflict on the tab
 * strip.
 *
 * The mediator collects the `EditorHandle.navigationRequests` of every editor from opening its tab until closing it. It
 * runs [navigateTo] for each request, so navigation never depends on a composable being on screen. The collaborators
 * and the sub-states use editors through the `EditorHandle` role. Only the editor area renders the full
 * `CodeEditorState`.
 *
 * Use the workbench from the UI thread only. Long-running work runs in the scope given to the factory. Blocking file
 * work runs on `WorkbenchServices.ioDispatcher`.
 */
public interface WorkbenchState : AutoCloseable {

    /**
     * The workspace of the opened folder. It is null while no folder is open. The window then shows the welcome view
     * with "Open Folder…" and the recent folders.
     */
    public val workspace: StateFlow<Workspace?>

    /** The language services of the current workspace, or null while no folder is open. */
    public val languageServices: StateFlow<LanguageServices?>

    /** The project tree. */
    public val projectTree: ProjectTreeState

    /** The open editor tabs and their editor states. */
    public val tabs: EditorTabsState

    /** The outline of the active document. */
    public val outline: OutlineState

    /** The problems of the workspace. */
    public val problems: ProblemsState

    /** The status bar. */
    public val statusBar: StatusBarState

    /**
     * The Language Tools Output. It shows what the language tools log, taken from
     * `WorkbenchServices.languageToolsOutput`. Its tool window is hidden by default and opens on demand from the View
     * menu.
     */
    public val output: OutputState

    /**
     * The runtime and the runs of the opened workspace, which the Runtime tool window shows. It is null while no
     * workspace is open. It is created when a workspace opens. Its sessions are shut down and its host is closed when
     * the workspace closes or the workbench is closed.
     */
    public val runtime: StateFlow<RuntimeState?>

    /**
     * The pending dialog. The workbench asks through it about unsaved changes, confirms deletions, asks for new names
     * and file names, shows errors and lets the user pick the descriptor to run.
     */
    public val dialogs: DialogState

    /**
     * The visibility and sizes of the tool windows, the theme and the window bounds as live UI state. The mediator
     * persists it into [settings], debounced and once more in [requestExit].
     */
    public val layout: LayoutState

    /** The persisted IDE settings, such as the recent folders, the font size and the layout as last persisted. */
    public val settings: StateFlow<IdeSettings>

    /**
     * The window title. It is the folder's name, a dash and `karpfen IDE` while a folder is open, and `karpfen IDE`
     * alone otherwise.
     */
    public val windowTitle: StateFlow<String>

    /** Loads the settings and restores the [layout]. Call it once before showing the window. */
    public suspend fun start()

    /**
     * Opens [path] as the workspace and records it as the most recent folder. The unsaved-changes flow runs for the
     * current workspace first. If the user cancels it, nothing happens. A path that is not a directory changes nothing
     * but the recent folders, which lose it, and shows an error dialog. That happens, for example, to a recent folder
     * that was deleted or unmounted since.
     */
    public fun openFolder(path: Path): Job

    /** Shows the folder chooser and opens the chosen folder. */
    public fun chooseAndOpenFolder(): Job

    /**
     * Opens [path] in an editor tab, or activates its tab if it is open already. This works for KMeta, KModel and
     * KStates files and for environment descriptors. Files that the editor cannot open come back as
     * `OpenResult.Unsupported`, such as a bridge configuration. They are opened in the system's default application
     * through `ProjectFileOperations.openExternally`. That also happens when the Problems view navigates to them.
     * Failures become an error dialog.
     */
    public fun openFile(path: Path): Job

    /**
     * Opens the file of [location] and moves the caret to the start of its range, scrolled to the centre. The range is
     * mapped into the document's current text with `EditableText.mapRange(range, version, lineage)`. When that returns
     * null, the range is clamped to the text instead. That happens for another lineage, a newer version, evicted
     * history, or a location without a version.
     *
     * The caret move, `scrollTo(center = true)` and the focus request are pending state of the editor. They therefore
     * take effect once a new tab is composed. Files that the editor cannot open are opened externally. The Problems
     * view calls it with `ProblemItem.location`, and the Outline view with `OutlineUiState.locationOf(node)`.
     */
    public fun navigateTo(location: Location): Job

    /**
     * Opens the project tree node [path] after a double-click or Enter. A directory of the tree is expanded or
     * collapsed at once through `ProjectTreeState.toggle`, and the returned job is already complete. Anything else is
     * opened with [openFile].
     */
    public fun openNode(path: Path): Job

    /**
     * Runs [command], which the user chose in the project tree's context menu from `ProjectTreeState.menuFor`. Each
     * command maps to its intent, which is [createFile], [rename], [delete] or [revealInFileManager].
     */
    public fun runTreeCommand(command: ProjectTreeCommand): Job

    /**
     * Creates a new file from a template, as chosen in the project tree's context menu. It asks for the name through
     * [DialogRequest.NewFile]. It then creates `FileTemplates.fileName(kind, name)` in [directory] through
     * `ProjectFileOperations.createFile`. The content is `FileTemplates.content(kind, name, templateContext)`. The
     * `TemplateContext` is built from the directory's current context and the syntax indexes of the latest analysis,
     * as documented on it. Then it refreshes the workspace, reveals the new file in the project tree and opens it with
     * [openFile]. Cancel does nothing. Failures, such as a file that exists already, become an error dialog.
     */
    public fun createFile(directory: Path, kind: FileTemplateKind): Job

    /**
     * Renames [path], which is a file or a directory, as chosen in the project tree's context menu. It asks for the new
     * name through [DialogRequest.Rename]. It then runs the unsaved-changes dialog over the dirty documents among the
     * affected open ones. For a file that is the file itself. For a directory it is every open document below it. The
     * dialog offers Save, Discard and Cancel, and Cancel aborts the rename.
     *
     * After that, it closes those documents, renames through `ProjectFileOperations.rename` and refreshes the
     * workspace. It reopens each document under its new path at its former tab position, and the active tab stays
     * active. Failures become an error dialog, and the documents are reopened under their old paths.
     */
    public fun rename(path: Path): Job

    /**
     * Deletes [path], as chosen in the project tree's context menu, after the user confirmed it through
     * [DialogRequest.ConfirmDelete]. The file goes to the trash when `ProjectFileOperations.supportsTrash` is true. The
     * intent closes the open documents of the file, or those below the directory, without saving. It then deletes
     * through `ProjectFileOperations.delete` and refreshes the workspace. Failures become an error dialog.
     */
    public fun delete(path: Path): Job

    /**
     * Shows [path] in Finder or Explorer through `ProjectFileOperations.revealInFileManager`. Does nothing where the
     * platform does not support it.
     */
    public fun revealInFileManager(path: Path): Job

    /**
     * Resolves the conflict of the open document [path] with a change on disk, through `OpenDocument.resolveConflict`.
     * The user chooses the resolution in the editor area's banner, which shows `EditorTab.conflict`.
     *
     * For a file changed on disk, the banner offers Reload and Keep mine. Reload is [ConflictResolution.RELOAD]. For a
     * file deleted on disk, the banner offers Close and Keep mine. Close is also [ConflictResolution.RELOAD]. The
     * document answers it with `ConflictResult.DeletedOnDisk`, and the mediator then closes the tab without saving.
     * `ConflictResult.Failed` becomes an error dialog. Without a conflict nothing happens.
     */
    public fun resolveConflict(path: Path, resolution: ConflictResolution): Job

    /** Saves the active document. It asks first if that would overwrite a change made on disk. */
    public fun saveActive(): Job

    /** Saves all dirty documents. It asks first about those whose save would overwrite a change made on disk. */
    public fun saveAll(): Job

    /**
     * Closes the tab of [path] and asks about unsaved changes first. Cancel keeps the tab open. While a close request
     * for the tab is queued or asking, a further request returns its job instead of asking again.
     */
    public fun closeTab(path: Path): Job

    /** Closes the tab that is active at the time of the call, as [closeTab] does. */
    public fun closeActiveTab(): Job

    /**
     * Makes the whole workbench one [step] larger or smaller, or sets it back to its normal size. The new scale is
     * written to the settings of the open project at once.
     */
    public fun zoomUi(step: ZoomStep): Job

    /**
     * Makes the text of the editors one [step] larger or smaller, or sets it back to its normal size. The new font
     * size is written to the settings of the open project at once.
     */
    public fun zoomEditor(step: ZoomStep): Job

    /**
     * Runs the unsaved-changes flow over every dirty document and persists the settings with the current [layout].
     * Returns true if the app may exit. It waits for a running intent flow first.
     */
    public suspend fun requestExit(): Boolean

    /**
     * Shuts down every session of the opened workspace's [runtime] and then closes its host. Closing the host stops a
     * container that the IDE started. All of this takes at most 15 seconds. Whatever is left after that is closed at
     * once without blocking the caller. The composition root calls it when the IDE exits, after [requestExit] allowed
     * the exit. Without a workspace it returns at once.
     */
    public suspend fun shutdownRuntime()

    /**
     * Registers three groups of commands in [registry]. The workbench commands of `WorkbenchCommandIds` include one
     * Open Recent command per recent folder and one toggle per `ToolWindowId`. The View commands drive [layout]. The
     * runtime commands of `RuntimeCommandIds` act on [runtime]. The editor commands act on the active editor. The
     * enablement and the recent folder commands are kept up to date. Commands call the non-suspending intents above.
     *
     * The [dialogs] are modal. While a dialog is shown, every command is disabled and does nothing when run. A shortcut
     * typed into a dialog therefore never acts on the workbench behind it.
     *
     * Editor commands are registered under their `EditorActionIds` with their `EditorActionTitles`, which the editor's
     * context menu shows too. They are forwarded to the active editor through `EditorHandle.execute(id)`. Their
     * shortcut is `WorkbenchServices.editorKeymap.shortcutFor(id)`. Every editor is created with that keymap, so it is
     * the single source of the shortcuts.
     *
     * An editor command is enabled when its id is in the active editor's `enabledActions` and the active editor
     * `isFocused`. FIND, REPLACE, FIND_NEXT, FIND_PREVIOUS, GO_TO_LINE and GO_TO_DEFINITION are the exception. They
     * open a popup or navigate, so they need no focus and request the focus for the editor first. FIND_NEXT and
     * FIND_PREVIOUS only do that while the find bar is closed. Typed into the bar, they keep the focus there. Cut,
     * Paste, Undo, Duplicate, Toggle Comment and the other editing commands from the menu therefore never act on an
     * editor while the Project tree, the Outline, Problems or a text field has the focus.
     *
     * Keys are routed like this. The focused component handles and consumes its keys first. The editor does that
     * through `CodeEditorState.handleKey`, and text fields do it natively. A menu accelerator only fires for
     * keystrokes that nobody consumed. One key press therefore runs an action only once.
     */
    public fun registerCommands(registry: CommandRegistry)

    /**
     * Closes the workspace and cancels the background work. It does not check for unsaved changes. [requestExit] does
     * that.
     */
    override fun close()
}

/** Creates the workbench state. All its work runs in [scope]. */
public fun WorkbenchState(scope: CoroutineScope, services: WorkbenchServices): WorkbenchState =
    DefaultWorkbenchState(scope, services)
