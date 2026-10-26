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

import io.karpfen.ide.core.workspace.document.OpenResult
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * The life of a document in an editor tab: [open] opens the document in the current workspace, creates its editor
 * (with the workbench's one keymap and the editor settings), adds the tab and starts collecting the editor's
 * navigation requests; [close] undoes all of it and closes the document (discarding unsaved changes — callers ask
 * first). Every tab has exactly one open document and one editor.
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

    /**
     * Opens [path] in a tab at [index] (default: after the active tab), or activates its tab. Null without a workspace;
     * otherwise the document manager's result (`Unsupported` and `Failed` open no tab).
     */
    suspend fun open(path: Path, index: Int? = null): OpenResult? {
        tabs.documentFor(path)?.let { document ->
            tabs.activate(path)
            return OpenResult.Opened(document)
        }
        val workspace = holder.workspace.value ?: return null
        val languageServices = holder.languageServices.value ?: return null
        val result = workspace.documents.open(path)
        if (result is OpenResult.Opened) {
            val document = result.document
            val editor = editorFactory.create(document.session, languageServices, scope, editorSettings, keymap)
            tabs.open(document, editor, workspace.root, index)
            navigation.start(editor)
        }
        return result
    }

    /** Closes the tab of [path] and its document without saving. */
    suspend fun close(path: Path) {
        tabs.editorFor(path)?.let(navigation::stop)
        tabs.remove(path)
        holder.workspace.value?.documents?.close(path)
    }

    /** Closes every tab without saving (the documents are closed with their workspace). */
    fun closeAllTabs() {
        tabs.documents().map { it.path }.forEach { path ->
            tabs.editorFor(path)?.let(navigation::stop)
            tabs.remove(path)
        }
    }
}
