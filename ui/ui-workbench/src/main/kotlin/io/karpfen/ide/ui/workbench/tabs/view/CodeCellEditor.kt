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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.document.registry.fragmentSupport
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.diagram.DiagramCellEditor
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.view.CodeEditor
import io.karpfen.ide.ui.editor.view.rememberCodeEditorState

/**
 * The editor of the code cells of the diagram of the file that [owner] edits, with the language [services] of the
 * workspace. A cell holds the code editor of the
 * IDE on a small document of its own, which starts with the code of the cell. The document is a piece of a text of
 * the language of the file. So it is coloured like the file and edited with the same keys, with undo, bracket pairs
 * and indentation. Nothing checks it, because a piece is no whole file.
 *
 * The document lives as long as the cell is open. It is named after the file with `.cell` put in front, next to the
 * file, and is never written to disk.
 */
internal class CodeCellEditor(private val owner: CodeEditorState, private val services: LanguageServices) : DiagramCellEditor {

    @Composable
    override fun Editor(code: String, onCode: (String) -> Unit, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val report by rememberUpdatedState(onCode)
        val session = remember(owner, code) {
            val file = owner.session.id.path
            val id = DocumentId(file.resolveSibling(CELL_PREFIX + file.fileName))
            DocumentSession(id, fragmentSupport(owner.session.support), EditableText(code), scope)
        }
        DisposableEffect(session) { onDispose { session.close() } }
        val editor = rememberCodeEditorState(session, services, owner.settings)
        LaunchedEffect(session) { session.text.snapshot.collect { report(it.text) } }
        LaunchedEffect(editor) { editor.requestFocus() }
        CodeEditor(editor, modifier)
    }

    private companion object {
        /** What stands in front of the name of a file in the name of the document of one of its code cells. */
        const val CELL_PREFIX = ".cell."
    }
}
