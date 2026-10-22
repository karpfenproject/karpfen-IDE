package io.karpfen.ide.ui.editor.view.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText

/**
 * Moves committed text out of the proxy text field into the editor: when [field] holds text and no composition (no
 * pending dead key or input-method pre-edit), the text is cleared from the field — synchronously, so the platform's
 * input method sees the empty field at once — its undo history dropped (the field's own undo must never bring typed
 * text back), and the text handed to [onCommit]. Composing text stays in the field, where it is shown, until the input
 * method commits it. Used from the UI thread.
 */
internal class CommittedTextForwarder(private val field: TextFieldState, private val onCommit: (String) -> Unit) {

    /**
     * Forwards the field's committed text, if any. Opts into the experimental `UndoState.clearHistory`, because the
     * field must not undo text it handed over.
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
