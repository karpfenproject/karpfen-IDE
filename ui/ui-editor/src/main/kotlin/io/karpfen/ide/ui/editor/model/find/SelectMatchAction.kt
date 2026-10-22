package io.karpfen.ide.ui.editor.model.find

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionContext

/**
 * Selects a find [match] (caret at its end), run through the editor's executor so the editor reveals it, whoever asked
 * for the selection (the find bar, the find-next keys or the menu). Caret only: no text change, no undo step. Not
 * registered (one instance per selection).
 */
internal class SelectMatchAction(val match: TextRange) : EditorAction {
    override val id: String = ID

    override fun execute(context: EditorActionContext): Boolean {
        val length = context.text.snapshot.value.length
        if (match.end > length) return false
        context.caret.select(match.start, match.end)
        return true
    }

    /** The shared id. */
    companion object {
        /** The id of every select-match action. */
        const val ID: String = "editor.find.selectMatch"
    }
}
