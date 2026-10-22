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
 * The ids of the enabled registered actions ([EditorPopupModel.enabledActions]): `EditorAction.isEnabled` in the
 * editor's context, which depends only on the carets, the text and the undo history (never on the clipboard or the
 * tokens). Recomputed when one of those changes (observed in [scope]) and synchronously by [refresh] after every intent
 * of the editor, so a context menu opened right after a click sees the new enablement. Used from the UI thread.
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
