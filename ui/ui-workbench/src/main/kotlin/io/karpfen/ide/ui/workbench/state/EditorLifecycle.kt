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
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.OpenResult
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.tabs.PaneId
import io.karpfen.ide.ui.workbench.tabs.PaneLayout
import io.karpfen.ide.ui.workbench.tabs.SplitSide
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The life of documents and their views in the editor panes. A view is the tab of a file in one pane, with an editor
 * of its own. Every editor is created with the workbench's single keymap and the editor settings, and the navigation
 * requests of each editor are collected from its creation until its tab closes.
 *
 * A file has one open document however many panes show it. [open] opens the document through the workspace's
 * document manager only for the first view of a file. Every later view gets a new editor on the same document session,
 * so text, undo history, dirty flag and conflict are shared while carets, scrolling and popups are not. [close]
 * closes the document when the last view of its file closes. The count of the views lives in the workbench and not in
 * the document manager: the views are the tabs of [tabs], which forgets a document with its last tab, and the document
 * manager stays a registry of open documents that knows nothing of panes. Closing discards unsaved changes, so callers
 * ask the user first, and only before the last view closes.
 */
internal class EditorLifecycle(
    private val scope: CoroutineScope,
    private val holder: WorkspaceHolder,
    private val tabs: EditorTabsState,
    private val editorFactory: EditorStateFactory,
    private val editorSettings: StateFlow<EditorSettings>,
    private val keymap: EditorKeymap,
    private val navigation: EditorNavigationCollector,
) {

    /** The settings of the editors of read-only documents: the usual ones, marked as read-only. */
    private val readOnlySettings: StateFlow<EditorSettings> = editorSettings
        .map { it.copy(readOnly = true) }
        .stateIn(scope, SharingStarted.Eagerly, editorSettings.value.copy(readOnly = true))

    /**
     * Opens [path] in a tab of [pane] at [index] and makes [pane] the active pane, or activates the tab of [path] there
     * if the pane has one already. Without an [index] the tab goes after the pane's active tab. A file that another
     * pane shows gets a new view of its open document. Without a [pane], and for a pane that is gone, as when it
     * closed while the request waited, the active pane takes the tab. Returns null without a workspace. Otherwise
     * returns the result of the document manager. `Unsupported` and `Failed` open no tab.
     */
    suspend fun open(path: Path, pane: PaneId? = null, index: Int? = null): OpenResult? {
        val workspace = holder.workspace.value ?: return null
        val services = holder.languageServices.value ?: return null
        val document = tabs.documentFor(path) ?: when (val result = workspace.documents.open(path)) {
            is OpenResult.Opened -> result.document
            else -> return result
        }
        val target = pane?.takeIf { tabs.layout.value.pane(it) != null } ?: tabs.activePane.value
        if (tabs.editorFor(target, path) != null) {
            tabs.activate(target, path)
        } else {
            val editor = createEditor(document, services)
            tabs.open(target, document, editor, workspace.root, index)
            navigation.start(editor)
        }
        return OpenResult.Opened(document)
    }

    /**
     * Splits [pane] towards [side]. The new pane shows the file of the pane's active tab in a new view of its
     * document, with the carets and the scroll position of the pane's editor and the same view mode, and becomes the
     * active pane. Its editor asks for the keyboard focus, which it takes once it is composed, so the user types on in
     * the new pane. The editor that had the focus loses it anyway, because its pane moves into the new split. Returns
     * false if the pane has no tab, lies too deep to be split again, or no folder is open.
     */
    fun split(pane: PaneId, side: SplitSide): Boolean {
        val services = holder.languageServices.value ?: return false
        val path = tabs.layout.value.pane(pane)?.active ?: return false
        val document = tabs.documentFor(path) ?: return false
        val editor = createEditor(document, services)
        tabs.editorFor(pane, path)?.let { source ->
            editor.caret.restore(source.caret.mementos())
            editor.scrollTo(editor.caret.primary.position)
        }
        if (tabs.split(pane, side, editor) == null) {
            editor.close()
            return false
        }
        navigation.start(editor)
        editor.requestFocus()
        return true
    }

    /** Closes the tab of [path] in [pane], and the document of [path] with its last view, without saving. */
    suspend fun close(pane: PaneId, path: Path) {
        tabs.editorFor(pane, path)?.let(navigation::stop)
        tabs.remove(pane, path)
        if (tabs.documentFor(path) == null) holder.workspace.value?.documents?.close(path)
    }

    /** Closes the tabs of [path] in every pane and its document, without saving. */
    suspend fun closeEverywhere(path: Path) {
        tabs.layout.value.panesShowing(path).forEach { close(it, path) }
    }

    /**
     * Shows the file at [new] in every view of the document of [old], after the file was renamed on disk. The
     * document of [new] is opened, every tab of [old] gets a new editor on it in its place, and the document of [old]
     * is closed without saving. If [new] cannot be opened in an editor, the tabs of [old] close.
     *
     * A rename that changes only the case of the name, on a file system whose paths ignore case, gives a [new] path
     * that equals [old]. The document manager would then answer the open request with the document of [old], which
     * still carries the old name and the discarded changes. So in that case the document of [old] is closed before
     * [new] is opened from disk.
     */
    suspend fun move(old: Path, new: Path) {
        val workspace = holder.workspace.value ?: return
        val services = holder.languageServices.value ?: return
        val document = tabs.documentFor(old) ?: return
        tabs.editorsOf(old).forEach(navigation::stop)
        if (old == new) workspace.documents.close(old)
        val result = workspace.documents.open(new)
        if (result is OpenResult.Opened) {
            tabs.replace(old, result.document, workspace.root) { createEditor(result.document, services).also(navigation::start) }
        } else {
            tabs.layout.value.panesShowing(old).forEach { tabs.remove(it, old) }
        }
        if (old != new && (result as? OpenResult.Opened)?.document !== document) workspace.documents.close(old)
    }

    /**
     * Arranges the panes as [layout] and opens the tabs it lists into their panes, in order, while no tab is open. A
     * file for which [accept] answers false, or that cannot be opened in an editor, is skipped. Then the stored active
     * tab of each pane and the stored active pane are activated where they were opened, and the panes that were left
     * without a tab disappear. The project flow restores the open files of a folder with it.
     */
    suspend fun restore(layout: PaneLayout, accept: suspend (Path) -> Boolean) {
        tabs.arrange(layout)
        for (pane in layout.panes) {
            for (path in pane.tabs) if (accept(path)) open(path, pane.id)
        }
        layout.panes.forEach { pane -> pane.active?.let { tabs.activate(pane.id, it) } }
        tabs.activatePane(layout.activePane)
        tabs.dropEmptyPanes()
    }

    /**
     * Closes every tab of every pane without saving and leaves one empty pane. The documents themselves are closed
     * together with their workspace.
     */
    fun closeAllTabs() {
        tabs.allEditors().forEach(navigation::stop)
        tabs.clear()
    }

    /** A new editor of [document] with the settings that fit its file. */
    private fun createEditor(document: OpenDocument, services: LanguageServices): CodeEditorState {
        val settings = if (document.fileKind.isReadOnly) readOnlySettings else editorSettings
        return editorFactory.create(document.session, services, scope, settings, keymap)
    }
}
