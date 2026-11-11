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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.EditorActionContext
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tracks the ids of the registered actions that are enabled, for [EditorPopupModel.enabledActions]. An action is
 * enabled when `EditorAction.isEnabled` is true in the editor's context. That check depends only on the carets, the
 * text and the undo history, and never on the clipboard or the tokens.
 *
 * The set is computed again when one of those inputs changes, which is observed in [scope]. [refresh] also computes it
 * synchronously after every intent of the editor. So a context menu opened right after a click sees the new
 * enablement. Use it from the UI thread.
 */
internal class ActionEnablement(
    private val actions: EditorActionRegistry,
    private val context: EditorActionContext,
    text: EditableText,
    caret: CaretModel,
    scope: CoroutineScope,
) {

    private val enabled = MutableStateFlow(compute())

    /** The enabled action ids. */
    val enabledActions: StateFlow<Set<String>> = enabled.asStateFlow()

    init {
        scope.launch {
            combine(caret.state, text.snapshot, text.undo.canUndo, text.undo.canRedo) { _, _, _, _ -> }.collect { refresh() }
        }
    }

    /** Recomputes the enabled ids now. */
    fun refresh() {
        enabled.value = compute()
    }

    /** The ids of the registered actions enabled in the editor's context now. */
    private fun compute(): Set<String> = actions.actions.filter { it.isEnabled(context) }.mapTo(HashSet()) { it.id }
}
