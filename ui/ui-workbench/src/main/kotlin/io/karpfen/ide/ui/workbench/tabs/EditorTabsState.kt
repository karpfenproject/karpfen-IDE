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
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The UI state of one editor tab.
 *
 * @property path the path of the open document, which identifies the tab.
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
 * The open editor tabs. The class holds their order, the active tab and the [CodeEditorState] of each tab. An editor
 * state is kept across tab switches and closed together with its tab. The UI state of each tab follows the dirty and
 * conflict flows of its document, collected in [scope].
 *
 * The read flows and the pure UI intents [activate] and [move] are public. Opening and removing tabs and handling the
 * documents are coordination concerns of the mediator. They involve the unsaved-changes dialog, closing the
 * `OpenDocument`, conflicts, and collecting the editor's navigation requests. [open], [remove] and [documentFor] are
 * therefore internal, and views close tabs through `WorkbenchState.closeTab`. The editor states are exposed as the full
 * `CodeEditorState`, because the editor area renders them. Everything else in the workbench uses them as
 * `EditorHandle`.
 *
 * Use the class from the UI thread only, which is the dispatcher of [scope].
 */
public class EditorTabsState(private val scope: CoroutineScope) {

    private val entries = ArrayList<TabEntry>()
    private val tabList = MutableStateFlow<List<EditorTab>>(emptyList())
    private val activePath = MutableStateFlow<Path?>(null)
    private val activeEditorState = MutableStateFlow<CodeEditorState?>(null)
    private val modes = MutableStateFlow<Map<Path, EditorViewMode>>(emptyMap())

    /** The tabs in display order. */
    public val tabs: StateFlow<List<EditorTab>> get() = tabList.asStateFlow()

    /** The path of the active tab, or null if no tab is open. */
    public val active: StateFlow<Path?> get() = activePath.asStateFlow()

    /** The editor state of the active tab, or null if no tab is open. */
    public val activeEditor: StateFlow<CodeEditorState?> get() = activeEditorState.asStateFlow()

    /** The editor state of the tab of [path], or null if there is no such tab. */
    public fun editorFor(path: Path): CodeEditorState? = entryOf(path)?.editor

    /**
     * The open document of the tab of [path], or null if there is no such tab. Only the mediator calls it. Views see
     * the document's state through [EditorTab] and change it through the `WorkbenchState` intents.
     */
    internal fun documentFor(path: Path): OpenDocument? = entryOf(path)?.document

    /**
     * How each file with a diagram is shown, by its path. A file without an entry shows its text alone. The entry of
     * a file goes away with its tab.
     */
    public val viewModes: StateFlow<Map<Path, EditorViewMode>> get() = modes.asStateFlow()

    /** Shows the file of [path] in [mode]. */
    public fun setViewMode(path: Path, mode: EditorViewMode) {
        if (entryOf(path) != null) modes.update { it + (path to mode) }
    }

    /** The open documents of all tabs, in tab order. Only the mediator calls it. */
    internal fun documents(): List<OpenDocument> = entries.map { it.document }

    /** The index of the tab of [path] in display order, or -1 if there is no such tab. Only the mediator calls it. */
    internal fun indexOf(path: Path): Int = entries.indexOfFirst { it.path == path }

    /**
     * Adds a tab and activates it. If a tab of the same path exists already, that tab is activated instead. The new tab
     * goes to [index], clamped to the valid range. Without an [index] it goes after the active tab, or to the end if
     * no tab is active. The caller creates [editor] only for a path without a tab. Only the mediator calls it.
     */
    internal fun open(document: OpenDocument, editor: CodeEditorState, workspaceRoot: Path, index: Int? = null) {
        val path = document.path
        if (entryOf(path) == null) {
            val activeIndex = activePath.value?.let(::indexOf) ?: -1
            val position = index?.coerceIn(0, entries.size) ?: if (activeIndex >= 0) activeIndex + 1 else entries.size
            val entry = TabEntry(document, editor, initialTab(document, workspaceRoot))
            entries.add(position, entry)
            entry.watcher = scope.launch { follow(entry) }
            publish()
        }
        activate(path)
    }

    /** Activates the tab of [path]. */
    public fun activate(path: Path) {
        val entry = entryOf(path) ?: return
        activePath.value = path
        activeEditorState.value = entry.editor
    }

    /**
     * Removes the tab of [path] and closes its editor state. If it was the active tab, its right neighbour becomes
     * active, or its left neighbour if it was the last tab. Only the mediator calls it.
     */
    internal fun remove(path: Path) {
        val index = indexOf(path)
        if (index < 0) return
        val entry = entries.removeAt(index)
        entry.watcher?.cancel()
        entry.editor.close()
        modes.update { it - path }
        publish()
        if (activePath.value == path) {
            val neighbour = entries.getOrNull(index) ?: entries.getOrNull(index - 1)
            if (neighbour != null) activate(neighbour.path) else clearActive()
        }
    }

    /** Moves a tab from index [from] to index [to], as a drag in the tab strip does. */
    public fun move(from: Int, to: Int) {
        if (from !in entries.indices || to !in entries.indices || from == to) return
        entries.add(to, entries.removeAt(from))
        publish()
    }

    private fun clearActive() {
        activePath.value = null
        activeEditorState.value = null
    }

    /** Keeps the tab state of [entry] in step with its document's dirty flag and conflict. */
    private suspend fun follow(entry: TabEntry) {
        combine(entry.document.isDirty, entry.document.conflict) { dirty, conflict -> dirty to conflict?.kind }
            .collect { (dirty, conflict) ->
                entry.tab = entry.tab.copy(isDirty = dirty, conflict = conflict)
                publish()
            }
    }

    /** Publishes the UI states of the tabs in display order. */
    private fun publish() {
        tabList.value = entries.map { it.tab }
    }

    private fun entryOf(path: Path): TabEntry? = entries.firstOrNull { it.path == path }

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

    /** One tab with its document, its editor, its current UI state and the job that follows the document's flows. */
    private class TabEntry(val document: OpenDocument, val editor: CodeEditorState, var tab: EditorTab) {
        /** The document's path, which is the tab's identity. */
        val path: Path get() = document.path

        /** The job that follows the document's dirty and conflict flows. It is cancelled when the tab is removed. */
        var watcher: Job? = null
    }
}
