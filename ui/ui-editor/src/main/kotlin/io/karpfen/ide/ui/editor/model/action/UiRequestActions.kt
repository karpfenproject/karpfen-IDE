package io.karpfen.ide.ui.editor.model.action

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
