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
