package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.ui.editor.state.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * UI state of one editor tab.
 *
 * @property title file name; [tooltip] the path relative to the workspace root.
 * @property hasConflict true while the document has an external-change conflict (banner with Reload / Keep mine).
 */
public data class EditorTab(
    public val path: Path,
    public val title: String,
    public val tooltip: String,
    public val language: KarpfenLanguage,
    public val isDirty: Boolean,
    public val hasConflict: Boolean,
)

/**
 * The open editor tabs (spec §8): order, active tab, and the [CodeEditorState] of each tab (kept across tab switches,
 * closed with the tab). Tab UI state follows the documents' dirty and conflict flows (collected in [scope]).
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

    /** The open document of the tab of [path], or null. */
    public fun documentFor(path: Path): OpenDocument? = TODO("WP-WORKBENCH: document lookup")

    /** Adds a tab after the active one (or activates an existing tab of the same path) and activates it. */
    public fun open(document: OpenDocument, editor: CodeEditorState, workspaceRoot: Path): Unit = TODO("WP-WORKBENCH: open tab")

    /** Activates the tab of [path]. */
    public fun activate(path: Path): Unit = TODO("WP-WORKBENCH: activate tab")

    /** Removes the tab of [path] and closes its editor state; the neighbour becomes active. */
    public fun remove(path: Path): Unit = TODO("WP-WORKBENCH: remove tab")

    /** Moves a tab from index [from] to index [to]. */
    public fun move(from: Int, to: Int): Unit = TODO("WP-WORKBENCH: move tab")
}
