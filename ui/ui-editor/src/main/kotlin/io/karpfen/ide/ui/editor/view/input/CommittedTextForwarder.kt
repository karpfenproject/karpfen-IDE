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
package io.karpfen.ide.ui.editor.view.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText

/**
 * Moves committed text out of the proxy text field into the editor. This happens when [field] holds text and no
 * composition, which means there is no pending dead key and no pre-edit text of an input method. The text is then
 * cleared from the field. That happens synchronously, so the input method of the platform sees the empty field at
 * once. The undo history of the field is dropped, because the field's own undo must never bring typed text back.
 * Finally the text is handed to [onCommit].
 *
 * Composing text stays in the field, where it is shown, until the input method commits it. Use the class from the UI
 * thread.
 */
internal class CommittedTextForwarder(private val field: TextFieldState, private val onCommit: (String) -> Unit) {

    /**
     * Forwards the committed text of the field, if there is any. It opts into the experimental
     * `UndoState.clearHistory`, because the field must not undo text it handed over.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun flush() {
        if (field.composition != null) return
        val committed = field.text.toString()
        if (committed.isEmpty()) return
        field.clearText()
        field.undoState.clearHistory()
        onCommit(committed)
    }
}
