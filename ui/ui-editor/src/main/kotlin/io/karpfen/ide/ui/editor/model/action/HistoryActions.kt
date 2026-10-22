package io.karpfen.ide.ui.editor.model.action

/**
 * Undo or redo, restoring the carets recorded with the step (`UndoResult.carets`); enabled only while the text's
 * `UndoManager.canUndo`/`canRedo` is true. A step recorded without carets leaves them to the caret model, which maps
 * them through the change.
 */
internal class HistoryAction(private val redo: Boolean) : EditorAction {
    override val id: String = if (redo) EditorActionIds.REDO else EditorActionIds.UNDO

    override fun isEnabled(context: EditorActionContext): Boolean =
        if (redo) context.text.undo.canRedo.value else context.text.undo.canUndo.value

    override fun execute(context: EditorActionContext): Boolean {
        val undo = context.text.undo
        val result = (if (redo) undo.redo() else undo.undo()) ?: return false
        result.carets?.let(context.caret::restore)
        return true
    }
}
