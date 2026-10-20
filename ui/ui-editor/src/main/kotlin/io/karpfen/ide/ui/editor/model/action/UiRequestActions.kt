package io.karpfen.ide.ui.editor.model.action

/** Requests handled by the UI (find bar, find next/previous, go to line, completion, go to definition). */
internal enum class UiRequest(val actionId: String) {
    FIND(EditorActionIds.FIND),
    REPLACE(EditorActionIds.REPLACE),
    FIND_NEXT(EditorActionIds.FIND_NEXT),
    FIND_PREVIOUS(EditorActionIds.FIND_PREVIOUS),
    GO_TO_LINE(EditorActionIds.GO_TO_LINE),
    COMPLETION(EditorActionIds.COMPLETION),
    GO_TO_DEFINITION(EditorActionIds.GO_TO_DEFINITION),
}

/** Forwards a [UiRequest] to `EditorActionContext.ui`. */
internal class UiRequestAction(private val request: UiRequest) : EditorAction {
    override val id: String = request.actionId

    override fun execute(context: EditorActionContext): Boolean {
        val ui = context.ui
        when (request) {
            UiRequest.FIND -> ui.openFind(replace = false)
            UiRequest.REPLACE -> ui.openFind(replace = true)
            UiRequest.FIND_NEXT -> ui.findNext()
            UiRequest.FIND_PREVIOUS -> ui.findPrevious()
            UiRequest.GO_TO_LINE -> ui.openGoToLine()
            UiRequest.COMPLETION -> ui.triggerCompletion()
            UiRequest.GO_TO_DEFINITION -> ui.goToDefinition()
        }
        return true
    }
}
