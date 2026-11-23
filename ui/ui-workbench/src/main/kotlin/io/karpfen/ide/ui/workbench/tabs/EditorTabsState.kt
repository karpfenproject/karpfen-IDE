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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorHandle
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The UI state of one editor tab. Every pane that shows the file shows the same value, because the document behind
 * the tabs is the same.
 *
 * @property path the path of the open document, which identifies the tab within its pane.
 * @property title the file name.
 * @property tooltip the path relative to the workspace root, or the whole path of a file outside it.
 * @property kind the kind of the file. It is derived from the file name with [FileKind.of], as everywhere in the
 *   workspace, so the tab shows the same icon as the project tree. Its [FileKind.language] is the document's language.
 * @property isDirty true while the document has unsaved changes.
 * @property conflict the kind of the document's conflict with a change on disk, or null if there is none. The editor
 *   area renders the banner from it. [ExternalChangeKind.MODIFIED] offers Reload and Keep mine.
 *   [ExternalChangeKind.DELETED] offers Close and Keep mine. The banner calls `WorkbenchState.resolveConflict` and
 *   never the `OpenDocument` itself. The tab strip marks the tab as well.
 */
public data class EditorTab(
    public val path: Path,
    public val title: String,
    public val tooltip: String,
    public val kind: FileKind,
    public val isDirty: Boolean,
    public val conflict: ExternalChangeKind? = null,
) {
    /** True while the document conflicts with a change on disk. */
    public val hasConflict: Boolean get() = conflict != null
}

/**
 * The UI state of one editor pane: what its tab strip, its editor and its bottom bar show.
 *
 * @property id the pane's identity. The intents of [EditorTabsState] and `WorkbenchState` that act on a given pane
 *   take it.
 * @property tabs the pane's tabs in display order.
 * @property active the file of the pane's active tab, or null if the pane has no tab.
 * @property viewModes how the pane shows each of its files with a diagram, by path. A file without an entry shows its
 *   text alone. Two panes may show the same file in different modes.
 */
public data class EditorPaneState(
    public val id: PaneId,
    public val tabs: List<EditorTab>,
    public val active: Path?,
    public val viewModes: Map<Path, EditorViewMode>,
) {
    /** How the pane shows its active file. */
    public val viewMode: EditorViewMode get() = active?.let(viewModes::get) ?: EditorViewMode.TEXT
}

/**
 * The open editor tabs, arranged in panes. The editor area is a tree of panes, split side by side or one above the
 * other, which [layout] describes. Every pane has its own tabs and its own active tab, and one pane is the active one.
 *
 * A file may have a tab in several panes. All of them show the one open document of the file, so its text, its undo
 * history, its dirty flag and its conflict are shared. Each tab, called a view here, has an editor state of its own,
 * with its own carets, selection, scroll position, find bar and popups, and its own view mode. A view's editor state
 * is kept across tab switches and closed together with its tab. The class keeps the document of a file while at least
 * one pane has a tab of it, and forgets it with its last view, so `documentFor(path)` answers null exactly when no
 * pane shows the file. The UI state of each tab follows the dirty and conflict flows of its document, collected in
 * [scope].
 *
 * The flows without a pane in their name, [tabs], [active], [activeEditor] and [viewModes], and the functions that
 * take no pane mean the active pane. The outline, the status bar, the editor commands, Save, Close Tab and the run
 * follow the active tab through them, whichever pane it is in. A view that draws every pane collects [layout] for the
 * tree and [panes] for the content of each pane, and takes the editor of a tab from [editorFor] with the pane's id.
 *
 * The pane of an editor that gains the keyboard focus becomes the active pane, with that editor's tab active in it.
 * Views make a pane active on a click with [activatePane] as well, because a diagram has no editor that takes the
 * focus.
 *
 * The read flows and the pure UI intents, such as [activate], [move], [resize] and the focus moves, are public.
 * Opening, splitting and removing tabs and handling the documents are coordination concerns of the mediator. They
 * involve the unsaved-changes dialog, closing the `OpenDocument`, conflicts, and collecting the editors' navigation
 * requests. Those functions are therefore internal, and views close and split through `WorkbenchState`. The editor
 * states are exposed as the full `CodeEditorState`, because the editor area renders them. Everything else in the
 * workbench uses them as `EditorHandle`.
 *
 * Use the class from the UI thread only, which is the dispatcher of [scope].
 */
public class EditorTabsState(private val scope: CoroutineScope) {

    private var arrangement = PaneLayout.single(PaneId(FIRST_ID))
    private var nextId = FIRST_ID + 1
    private val documents = HashMap<Path, DocumentEntry>()
    private val views = HashMap<ViewKey, ViewEntry>()
    private val modes = HashMap<ViewKey, EditorViewMode>()

    private val layoutState = MutableStateFlow(arrangement)
    private val paneStates = MutableStateFlow(statesOf(arrangement))
    private val activePaneId = MutableStateFlow(arrangement.activePane)
    private val tabList = MutableStateFlow<List<EditorTab>>(emptyList())
    private val activePath = MutableStateFlow<Path?>(null)
    private val activeModes = MutableStateFlow<Map<Path, EditorViewMode>>(emptyMap())
    private val activeEditorState = MutableStateFlow<CodeEditorState?>(null)

    /** The arrangement of the panes: the tree with the tabs of every pane, and the active pane. */
    public val layout: StateFlow<PaneLayout> get() = layoutState.asStateFlow()

    /** The UI state of every pane, by its id. */
    public val panes: StateFlow<Map<PaneId, EditorPaneState>> get() = paneStates.asStateFlow()

    /** The id of the active pane. */
    public val activePane: StateFlow<PaneId> get() = activePaneId.asStateFlow()

    /** The tabs of the active pane in display order. */
    public val tabs: StateFlow<List<EditorTab>> get() = tabList.asStateFlow()

    /** The path of the active tab of the active pane, or null if that pane has no tab. */
    public val active: StateFlow<Path?> get() = activePath.asStateFlow()

    /** The editor state of the active tab of the active pane, or null if that pane has no tab. */
    public val activeEditor: StateFlow<CodeEditorState?> get() = activeEditorState.asStateFlow()

    /**
     * How the active pane shows each of its files with a diagram, by path. A file without an entry shows its text
     * alone. The entry of a file goes away with its tab.
     */
    public val viewModes: StateFlow<Map<Path, EditorViewMode>> get() = activeModes.asStateFlow()

    /** The editor state of the tab of [path] in the active pane, or null if that pane has no such tab. */
    public fun editorFor(path: Path): CodeEditorState? = editorFor(arrangement.activePane, path)

    /** The editor state of the tab of [path] in the pane [pane], or null if there is no such tab. */
    public fun editorFor(pane: PaneId, path: Path): CodeEditorState? = views[ViewKey(pane, path)]?.editor

    /**
     * The open document of [path], or null if no pane has a tab of it. Only the mediator calls it. Views see the
     * document's state through [EditorTab] and change it through the `WorkbenchState` intents.
     */
    internal fun documentFor(path: Path): OpenDocument? = documents[path]?.document

    /**
     * The open documents of all panes, each once, in focus order of the panes and tab order within a pane. Only the
     * mediator calls it.
     */
    internal fun documents(): List<OpenDocument> =
        arrangement.panes.flatMap { it.tabs }.distinct().mapNotNull { documents[it]?.document }

    /** The editor states of the tabs of [path] in every pane, in focus order. Only the mediator calls it. */
    internal fun editorsOf(path: Path): List<CodeEditorState> =
        arrangement.panesShowing(path).mapNotNull { editorFor(it, path) }

    /** The pane whose tab has [editor], or null if no tab has it. Only the mediator calls it. */
    internal fun paneOf(editor: EditorHandle): PaneId? = views.entries.firstOrNull { it.value.editor === editor }?.key?.pane

    /** The editor states of every tab of every pane. Only the mediator calls it. */
    internal fun allEditors(): List<CodeEditorState> = views.values.map { it.editor }

    /**
     * The index of the tab of [path] in the active pane in display order, or -1 if that pane has no such tab. Only the
     * mediator calls it.
     */
    internal fun indexOf(path: Path): Int = arrangement.active.tabs.indexOf(path)

    /** Shows the file of [path] in [mode] in the active pane. */
    public fun setViewMode(path: Path, mode: EditorViewMode) {
        setViewMode(arrangement.activePane, path, mode)
    }

    /** Shows the file of [path] in [mode] in the pane [pane]. A pane without a tab of [path] does not change. */
    public fun setViewMode(pane: PaneId, path: Path, mode: EditorViewMode) {
        val key = ViewKey(pane, path)
        if (key !in views) return
        modes[key] = mode
        publish()
    }

    /** Adds a tab to the active pane, as [open] with a pane does. Only the mediator calls it. */
    internal fun open(document: OpenDocument, editor: CodeEditorState, workspaceRoot: Path, index: Int? = null) {
        open(arrangement.activePane, document, editor, workspaceRoot, index)
    }

    /**
     * Adds a tab of [document] with [editor] to the pane [pane] and activates it there, and makes [pane] the active
     * pane. If the pane has a tab of the same path already, that tab is activated instead. The new tab goes to
     * [index], clamped to the valid range. Without an [index] it goes after the pane's active tab, or to the end if the
     * pane has no tab. The caller creates [editor] only for a path without a tab in [pane], on the session of the
     * document that other panes show already, if any. Only the mediator calls it.
     */
    internal fun open(pane: PaneId, document: OpenDocument, editor: CodeEditorState, workspaceRoot: Path, index: Int? = null) {
        val target = arrangement.pane(pane) ?: return
        val path = document.path
        val key = ViewKey(pane, path)
        if (key !in views) {
            documents.getOrPut(path) { documentEntry(document, workspaceRoot) }
            views[key] = viewEntry(key, editor)
            val position = index ?: target.active?.let { target.tabs.indexOf(it) + 1 } ?: target.tabs.size
            arrangement = arrangement.withTab(pane, path, position)
        }
        activate(pane, path)
    }

    /** Activates the tab of [path] in the active pane. */
    public fun activate(path: Path) {
        activate(arrangement.activePane, path)
    }

    /** Activates the tab of [path] in the pane [pane] and makes [pane] the active pane. */
    public fun activate(pane: PaneId, path: Path) {
        if (ViewKey(pane, path) !in views) return
        arrangement = arrangement.withActiveTab(pane, path).withActivePane(pane)
        publish()
    }

    /**
     * Activates the tab of [path] where the user can see it: in the active pane if it has one, else in the first pane
     * in focus order that has one, which then becomes the active pane. Only the mediator calls it.
     */
    internal fun reveal(path: Path) {
        val pane = arrangement.activePane.takeIf { ViewKey(it, path) in views } ?: arrangement.panesShowing(path).firstOrNull()
        pane?.let { activate(it, path) }
    }

    /** Makes the pane [pane] the active pane, as a click into it does. */
    public fun activatePane(pane: PaneId) {
        if (arrangement.pane(pane) == null || arrangement.activePane == pane) return
        arrangement = arrangement.withActivePane(pane)
        publish()
    }

    /**
     * Makes the pane after the active one in focus order active and asks its editor for the keyboard focus, as
     * [focusPane] describes. After the last pane comes the first.
     */
    public fun focusNextPane() {
        focusPane(arrangement.next(arrangement.activePane))
    }

    /**
     * Makes the pane before the active one in focus order active and asks its editor for the keyboard focus, as
     * [focusPane] describes. Before the first pane comes the last.
     */
    public fun focusPreviousPane() {
        focusPane(arrangement.previous(arrangement.activePane))
    }

    /**
     * Splits the pane [pane] towards [side], as [PaneLayout] describes. The new pane shows the file of the pane's
     * active tab with [editor], in the view mode of that tab, and becomes the active pane. Returns the new pane's id,
     * or null if the layout cannot split [pane]: it does not exist, has no tab or lies too deep. Then [editor] is not
     * taken, and the caller closes it. The caller creates [editor] on the session of the active tab's document. Only
     * the mediator calls it.
     */
    internal fun split(pane: PaneId, side: SplitSide, editor: CodeEditorState): PaneId? {
        val path = arrangement.pane(pane)?.active ?: return null
        val fresh = PaneId(nextId)
        val split = arrangement.split(pane, side, fresh, SplitId(nextId + 1))
        if (split == arrangement) return null
        nextId += 2
        arrangement = split
        val key = ViewKey(fresh, path)
        views[key] = viewEntry(key, editor)
        modes[ViewKey(pane, path)]?.let { modes[key] = it }
        publish()
        return fresh
    }

    /** Sets the share of the split [split], as a drag of the border between its parts does. */
    public fun resize(split: SplitId, share: Float) {
        val resized = arrangement.resized(split, share)
        if (resized == arrangement) return
        arrangement = resized
        publish()
    }

    /**
     * Removes the tab of [path] from the pane [pane] and closes its editor state. The pane's next tab becomes active,
     * and a pane left without tabs disappears unless it is the only one, as [PaneLayout] describes. With its last view
     * the document of [path] is forgotten as well. Only the mediator calls it, and it closes the document itself.
     *
     * When the pane disappears while an editor of the editor area has the keyboard focus, the editor of the active
     * pane is asked for the focus, as [focusPane] describes. The panes that stay move in the tree, and the editor that
     * had the focus loses it with that move, also when its pane stays the active one.
     */
    internal fun remove(pane: PaneId, path: Path) {
        val key = ViewKey(pane, path)
        val view = views[key] ?: return
        val editorHadFocus = views.values.any { it.editor.isFocused.value }
        views.remove(key)
        view.close()
        modes.remove(key)
        arrangement = arrangement.withoutTab(pane, path)
        if (arrangement.panesShowing(path).isEmpty()) documents.remove(path)?.watcher?.cancel()
        publish()
        if (editorHadFocus && arrangement.pane(pane) == null) focusPane(arrangement.activePane)
    }

    /**
     * Puts [document] in the place of the document of [old] in every pane that shows [old], as after a rename. Each
     * tab keeps its position, its active state and its view mode, and gets a new editor state from [editorFor] with
     * the pane's id. The editor states of [old] are closed. A pane that shows the new path already keeps that tab and
     * loses the tab of [old]. Only the mediator calls it, and it closes the document of [old] itself.
     */
    internal fun replace(old: Path, document: OpenDocument, workspaceRoot: Path, editorFor: (PaneId) -> CodeEditorState) {
        val new = document.path
        val panes = arrangement.panesShowing(old)
        documents.remove(old)?.watcher?.cancel()
        documents.getOrPut(new) { documentEntry(document, workspaceRoot) }
        panes.forEach { pane ->
            val oldKey = ViewKey(pane, old)
            views.remove(oldKey)?.close()
            val mode = modes.remove(oldKey)
            val newKey = ViewKey(pane, new)
            if (newKey !in views) {
                views[newKey] = viewEntry(newKey, editorFor(pane))
                mode?.let { modes[newKey] = it }
            }
        }
        arrangement = arrangement.withPathReplaced(old, new)
        publish()
    }

    /** Moves a tab of the active pane from index [from] to index [to], as a drag in the tab strip does. */
    public fun move(from: Int, to: Int) {
        move(arrangement.activePane, from, to)
    }

    /** Moves a tab of the pane [pane] from index [from] to index [to], as a drag in the tab strip does. */
    public fun move(pane: PaneId, from: Int, to: Int) {
        val moved = arrangement.withMovedTab(pane, from, to)
        if (moved == arrangement) return
        arrangement = moved
        publish()
    }

    /**
     * Closes every tab of every pane with its editor state and leaves one empty pane. The documents stay open, so the
     * caller closes them. Only the mediator calls it.
     */
    internal fun clear() {
        views.values.forEach(ViewEntry::close)
        views.clear()
        modes.clear()
        documents.values.forEach { it.watcher?.cancel() }
        documents.clear()
        arrangement = PaneLayout.single(PaneId(nextId++))
        publish()
    }

    /**
     * Takes the panes and splits of [layout], every pane without tabs, while no tab is open. The mediator then opens
     * the tabs into their panes and calls [dropEmptyPanes]. New ids are chosen above those of [layout].
     */
    internal fun arrange(layout: PaneLayout) {
        check(views.isEmpty()) { "Panes are arranged only while no tab is open" }
        arrangement = layout.emptied()
        nextId = maxOf(nextId, layout.greatestId + 1)
        publish()
    }

    /** Removes the panes without tabs, as [PaneLayout.withoutEmptyPanes] describes. Only the mediator calls it. */
    internal fun dropEmptyPanes() {
        arrangement = arrangement.withoutEmptyPanes()
        publish()
    }

    /**
     * Activates [pane] and asks the editor of its active tab for the keyboard focus, unless the pane shows that file's
     * diagram alone. An editor that is not shown would keep the request until it shows, so the view of such a pane
     * holds the focus itself.
     */
    private fun focusPane(pane: PaneId) {
        activatePane(pane)
        val active = arrangement.active.active ?: return
        if (modes[ViewKey(pane, active)] != EditorViewMode.DIAGRAM) activeEditorState.value?.requestFocus()
    }

    /** Keeps the tab state of [entry] in step with its document's dirty flag and conflict. */
    private suspend fun follow(entry: DocumentEntry) {
        combine(entry.document.isDirty, entry.document.conflict) { dirty, conflict -> dirty to conflict?.kind }
            .collect { (dirty, conflict) ->
                entry.tab = entry.tab.copy(isDirty = dirty, conflict = conflict)
                publish()
            }
    }

    /**
     * Publishes the layout, the UI states of the panes and the flows of the active pane. The active editor is set last,
     * so a collector of [activeEditor] reads the matching [active] path.
     */
    private fun publish() {
        val states = statesOf(arrangement)
        val activeState = states.getValue(arrangement.activePane)
        layoutState.value = arrangement
        paneStates.value = states
        activePaneId.value = arrangement.activePane
        tabList.value = activeState.tabs
        activePath.value = activeState.active
        activeModes.value = activeState.viewModes
        activeEditorState.value = activeState.active?.let { editorFor(arrangement.activePane, it) }
    }

    /** The UI states of the panes of [layout]. */
    private fun statesOf(layout: PaneLayout): Map<PaneId, EditorPaneState> = layout.panes.associate { pane ->
        val paneModes = pane.tabs.mapNotNull { path -> modes[ViewKey(pane.id, path)]?.let { path to it } }.toMap()
        pane.id to EditorPaneState(pane.id, pane.tabs.mapNotNull { documents[it]?.tab }, pane.active, paneModes)
    }

    /** A new entry for [document] that follows its flows. */
    private fun documentEntry(document: OpenDocument, workspaceRoot: Path): DocumentEntry =
        DocumentEntry(document, initialTab(document, workspaceRoot)).also { entry -> entry.watcher = scope.launch { follow(entry) } }

    /** A new entry for the view [key] with [editor]. Its pane becomes active whenever [editor] gains the focus. */
    private fun viewEntry(key: ViewKey, editor: CodeEditorState): ViewEntry {
        val focus = scope.launch { editor.isFocused.collect { focused -> if (focused) activate(key.pane, key.path) } }
        return ViewEntry(editor, focus)
    }

    /**
     * The first UI state of the tab of [document]. The tooltip is the path relative to [workspaceRoot] where possible.
     */
    private fun initialTab(document: OpenDocument, workspaceRoot: Path): EditorTab {
        val path = document.path
        val title = path.fileName?.toString() ?: path.toString()
        val tooltip = if (path.startsWith(workspaceRoot) && path != workspaceRoot) {
            workspaceRoot.relativize(path).joinToString("/")
        } else {
            path.toString()
        }
        return EditorTab(
            path = path,
            title = title,
            tooltip = tooltip,
            kind = FileKind.of(title),
            isDirty = document.isDirty.value,
            conflict = document.conflict.value?.kind,
        )
    }

    /** The tab of [path] in the pane [pane]. */
    private data class ViewKey(val pane: PaneId, val path: Path)

    /** The editor of one tab and the job that makes its pane active when the editor gains the focus. */
    private class ViewEntry(val editor: CodeEditorState, val focus: Job) {
        /** Stops following the focus and closes the editor. */
        fun close() {
            focus.cancel()
            editor.close()
        }
    }

    /** One open document with the UI state of its tabs and the job that follows the document's flows. */
    private class DocumentEntry(val document: OpenDocument, var tab: EditorTab) {
        /** The job that follows the document's dirty and conflict flows. It is cancelled with the last view. */
        var watcher: Job? = null
    }

    private companion object {
        /** The id of the first pane. Later panes and splits count up from it. */
        const val FIRST_ID = 0
    }
}
