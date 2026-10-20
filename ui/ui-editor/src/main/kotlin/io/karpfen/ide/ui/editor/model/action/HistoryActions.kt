package io.karpfen.ide.ui.editor.model.action

/**
 * Undo or redo (spec §7.1), restoring the carets recorded with the step (`UndoResult.carets`); enabled only while the
 * text's `UndoManager.canUndo`/`canRedo` is true.
 */
internal class HistoryAction(private val redo: Boolean) : EditorAction {
    override val id: String = if (redo) EditorActionIds.REDO else EditorActionIds.UNDO

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: ${if (redo) "redo" else "undo"}")
}
