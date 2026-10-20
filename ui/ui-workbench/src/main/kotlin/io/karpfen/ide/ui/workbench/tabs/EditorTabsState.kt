package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.ui.editor.state.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * UI state of one editor tab.
 *
 * @property title file name; [tooltip] the path relative to the workspace root.
 * @property conflict the kind of the document's external-change conflict, or null: the editor area renders the banner
 *   from it — [ExternalChangeKind.MODIFIED] → Reload / Keep mine, [ExternalChangeKind.DELETED] → Close / Keep mine —
 *   and calls `WorkbenchState.resolveConflict`, never the `OpenDocument` itself.
 */
public data class EditorTab(
    public val path: Path,
    public val title: String,
    public val tooltip: String,
    public val language: KarpfenLanguage,
    public val isDirty: Boolean,
    public val conflict: ExternalChangeKind? = null,
) {
    /** True while the document has an external-change conflict. */
    public val hasConflict: Boolean get() = conflict != null
}

/**
 * The open editor tabs (spec §8): order, active tab, and the [CodeEditorState] of each tab (kept across tab switches,
 * closed with the tab). Tab UI state follows the documents' dirty and conflict flows (collected in [scope]).
 *
 * Public: the read flows and the pure UI intents ([activate], [move]). Opening and removing tabs and the documents
 * are coordination concerns of the mediator (unsaved-changes dialog, closing the `OpenDocument`, conflicts, collecting
 * the editor's navigation requests), so [open], [remove] and [documentFor] are internal; views close tabs through
 * `WorkbenchState.closeTab` (decision 31 of the work-package plan). The editor states are the full `CodeEditorState`
 * because the editor area renders them; everything else in the workbench uses them as `EditorHandle`.
 */
public class EditorTabsState(private val scope: CoroutineScope) {

    /** Tabs in display order. */
    public val tabs: StateFlow<List<EditorTab>> get() = TODO("WP-WORKBENCH: tabs")

    /** Path of the active tab, or null. */
    public val active: StateFlow<Path?> get() = TODO("WP-WORKBENCH: active tab")

    /** The editor state of the active tab, or null. */
    public val activeEditor: StateFlow<CodeEditorState?> get() = TODO("WP-WORKBENCH: active editor")

    /** The editor state of the tab of [path], or null. */
    public fun editorFor(path: Path): CodeEditorState? = TODO("WP-WORKBENCH: editor lookup")

    /**
     * The open document of the tab of [path], or null. Mediator only: views see the document's state through
     * [EditorTab] and change it through the `WorkbenchState` intents (decision 43 of the work-package plan).
     */
    internal fun documentFor(path: Path): OpenDocument? = TODO("WP-WORKBENCH: document lookup")

    /** Adds a tab after the active one (or activates an existing tab of the same path) and activates it. Mediator only. */
    internal fun open(document: OpenDocument, editor: CodeEditorState, workspaceRoot: Path): Unit = TODO("WP-WORKBENCH: open tab")

    /** Activates the tab of [path]. */
    public fun activate(path: Path): Unit = TODO("WP-WORKBENCH: activate tab")

    /** Removes the tab of [path] and closes its editor state; the neighbour becomes active. Mediator only. */
    internal fun remove(path: Path): Unit = TODO("WP-WORKBENCH: remove tab")

    /** Moves a tab from index [from] to index [to] (drag in the tab strip). */
    public fun move(from: Int, to: Int): Unit = TODO("WP-WORKBENCH: move tab")
}
