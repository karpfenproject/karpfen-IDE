package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.editor.model.caret.CaretMovement

/** Moves the carets by [movement], extending the selection when [extendSelection] (page size: `context.pageLines`). */
internal class CaretMoveAction(
    override val id: String,
    private val movement: CaretMovement,
    private val extendSelection: Boolean,
) : EditorAction {
    override fun execute(context: EditorActionContext): Boolean {
        context.caret.move(movement, extendSelection, context.pageLines)
        return true
    }
}

/** Selects the whole text. */
internal class SelectAllAction : EditorAction {
    override val id: String = EditorActionIds.SELECT_ALL

    override fun execute(context: EditorActionContext): Boolean {
        context.caret.selectAll()
        return true
    }
}
