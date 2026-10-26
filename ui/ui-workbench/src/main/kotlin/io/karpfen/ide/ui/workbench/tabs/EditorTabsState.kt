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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * UI state of one editor tab.
 *
 * @property path the path of the open document, which identifies the tab.
 * @property title the file name.
 * @property tooltip the path relative to the workspace root, or the whole path of a file outside it.
 * @property kind the kind of the file, by its name as everywhere in the workspace ([FileKind.of]), so the tab shows the
 *   icon the project tree shows for the file; its [FileKind.language] is the document's language.
 * @property isDirty true while the document has unsaved changes.
 * @property conflict the kind of the document's external-change conflict, or null: the editor area renders the banner
 *   from it — [ExternalChangeKind.MODIFIED] → Reload / Keep mine, [ExternalChangeKind.DELETED] → Close / Keep mine —
 *   and calls `WorkbenchState.resolveConflict`, never the `OpenDocument` itself; the tab strip marks the tab.
 */
public data class EditorTab(
    public val path: Path,
    public val title: String,
    public val tooltip: String,
    public val kind: FileKind,
    public val isDirty: Boolean,
    public val conflict: ExternalChangeKind? = null,
) {
    /** True while the document has an external-change conflict. */
    public val hasConflict: Boolean get() = conflict != null
}

/**
 * The open editor tabs: order, active tab, and the [CodeEditorState] of each tab (kept across tab switches, closed with
 * the tab). Tab UI state follows the documents' dirty and conflict flows (collected in [scope]).
 *
 * Public: the read flows and the pure UI intents ([activate], [move]). Opening and removing tabs and the documents are
 * coordination concerns of the mediator (unsaved-changes dialog, closing the `OpenDocument`, conflicts, collecting the
 * editor's navigation requests), so [open], [remove] and [documentFor] are internal; views close tabs through
 * `WorkbenchState.closeTab`. The editor states are the full `CodeEditorState` because the editor area renders them;
 * everything else in the workbench uses them as `EditorHandle`.
 *
 * Used from the UI thread (the dispatcher of [scope]).
 */
public class EditorTabsState(private val scope: CoroutineScope) {

    private val entries = ArrayList<TabEntry>()
    private val tabList = MutableStateFlow<List<EditorTab>>(emptyList())
    private val activePath = MutableStateFlow<Path?>(null)
    private val activeEditorState = MutableStateFlow<CodeEditorState?>(null)

    /** Tabs in display order. */
    public val tabs: StateFlow<List<EditorTab>> get() = tabList.asStateFlow()

    /** Path of the active tab, or null. */
    public val active: StateFlow<Path?> get() = activePath.asStateFlow()

    /** The editor state of the active tab, or null. */
    public val activeEditor: StateFlow<CodeEditorState?> get() = activeEditorState.asStateFlow()

    /** The editor state of the tab of [path], or null. */
    public fun editorFor(path: Path): CodeEditorState? = entryOf(path)?.editor

    /**
     * The open document of the tab of [path], or null. Mediator only: views see the document's state through
     * [EditorTab] and change it through the `WorkbenchState` intents.
     */
    internal fun documentFor(path: Path): OpenDocument? = entryOf(path)?.document

    /** The open documents of all tabs, in tab order. Mediator only. */
    internal fun documents(): List<OpenDocument> = entries.map { it.document }

    /** The index of the tab of [path] in display order, or -1. Mediator only. */
    internal fun indexOf(path: Path): Int = entries.indexOfFirst { it.path == path }

    /**
     * Adds a tab (or activates an existing tab of the same path) and activates it: at [index] (clamped) if given, else
     * after the active tab (at the end without one). The caller creates [editor] only for a path without a tab.
     * Mediator only.
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

    /** Removes the tab of [path] and closes its editor state; the neighbour becomes active. Mediator only. */
    internal fun remove(path: Path) {
        val index = indexOf(path)
        if (index < 0) return
        val entry = entries.removeAt(index)
        entry.watcher?.cancel()
        entry.editor.close()
        publish()
        if (activePath.value == path) {
            val neighbour = entries.getOrNull(index) ?: entries.getOrNull(index - 1)
            if (neighbour != null) activate(neighbour.path) else clearActive()
        }
    }

    /** Moves a tab from index [from] to index [to] (drag in the tab strip). */
    public fun move(from: Int, to: Int) {
        if (from !in entries.indices || to !in entries.indices || from == to) return
        entries.add(to, entries.removeAt(from))
        publish()
    }

    private fun clearActive() {
        activePath.value = null
        activeEditorState.value = null
    }

    /** Keeps [entry]'s tab state in step with its document's dirty flag and conflict. */
    private suspend fun follow(entry: TabEntry) {
        combine(entry.document.isDirty, entry.document.conflict) { dirty, conflict -> dirty to conflict?.kind }
            .collect { (dirty, conflict) ->
                entry.tab = entry.tab.copy(isDirty = dirty, conflict = conflict)
                publish()
            }
    }

    /** Publishes the tabs' UI states in display order. */
    private fun publish() {
        tabList.value = entries.map { it.tab }
    }

    private fun entryOf(path: Path): TabEntry? = entries.firstOrNull { it.path == path }

    /** The first UI state of [document]'s tab; the tooltip is the path relative to [workspaceRoot] where possible. */
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

    /** One tab: its document, editor, current UI state and the job following the document's flows. */
    private class TabEntry(val document: OpenDocument, val editor: CodeEditorState, var tab: EditorTab) {
        /** The document's path, the tab's identity. */
        val path: Path get() = document.path

        /** The job following the document's dirty and conflict flows; cancelled when the tab is removed. */
        var watcher: Job? = null
    }
}
