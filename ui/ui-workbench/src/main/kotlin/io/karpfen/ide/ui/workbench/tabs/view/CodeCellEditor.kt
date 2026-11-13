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
package io.karpfen.ide.ui.workbench.tabs.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.notation.diagram.edit.DiagramCell
import io.karpfen.ide.ui.diagram.DiagramCellEditor
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.CodeEditor
import io.karpfen.ide.ui.editor.view.rememberCodeEditorState
import io.karpfen.ide.ui.workbench.tabs.CodeCellSession

/**
 * The editor of the code cells of the diagram of the file that [owner] edits, with the language [services] of the
 * [workspace]. A cell holds the code editor of the IDE on a [CodeCellSession], which starts with the code of the cell
 * and sees it in its place in the file. So the code is coloured like the file and edited with the same keys, with undo,
 * bracket pairs and indentation, and it has the completion, the hover and the checks it has in the text of the file.
 *
 * The session lives as long as the cell is open and is never written to disk.
 */
internal class CodeCellEditor(
    private val owner: CodeEditorState,
    private val services: LanguageServices,
    private val workspace: Workspace?,
) : DiagramCellEditor {

    @Composable
    override fun Editor(cell: DiagramCell, onCode: (String) -> Unit, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val report by rememberUpdatedState(onCode)
        val opened = remember(owner, cell.key, cell.code) { CodeCellSession(owner.session, cell, services, workspace, scope) }
        DisposableEffect(opened) { onDispose { opened.close() } }
        val editor = rememberCodeEditorState(opened.session, opened.services, owner.settings)
        LaunchedEffect(opened) { opened.session.text.snapshot.collect { report(it.text) } }
        LaunchedEffect(editor) { editor.requestFocus() }
        CodeEditor(editor, modifier)
    }
}
