package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.editor.model.caret.CaretMovement

/** Moves the carets by [movement], extending the selection when [extendSelection]. */
internal class CaretMoveAction(
    override val id: String,
    private val movement: CaretMovement,
    private val extendSelection: Boolean,
) : EditorAction {
    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: $id")
}

/** Selects the whole text. */
internal class SelectAllAction : EditorAction {
    override val id: String = EditorActionIds.SELECT_ALL

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: select all")
}

/** Requests handled by the UI (find bar, go to line, completion, go to definition). */
internal enum class UiRequest(val actionId: String) {
    FIND(EditorActionIds.FIND),
    REPLACE(EditorActionIds.REPLACE),
    GO_TO_LINE(EditorActionIds.GO_TO_LINE),
    COMPLETION(EditorActionIds.COMPLETION),
    GO_TO_DEFINITION(EditorActionIds.GO_TO_DEFINITION),
}

/** Forwards a [UiRequest] to `EditorActionContext.ui`. */
internal class UiRequestAction(private val request: UiRequest) : EditorAction {
    override val id: String = request.actionId

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: ${request.name}")
}
