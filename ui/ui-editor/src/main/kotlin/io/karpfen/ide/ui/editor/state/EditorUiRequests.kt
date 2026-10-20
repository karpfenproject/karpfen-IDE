package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.EditorUiPort
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.completion.CompletionTrigger
import io.karpfen.ide.ui.editor.model.find.FindModel

/**
 * The editor's [EditorUiPort]: what actions ask of the UI, answered with the editor's models and overlay state.
 *
 * - [openFind] seeds the find query from a one-line selection (`FindModel.setQuery`, which keeps that selection as the
 *   current match) before opening the bar, so the overlay state holds no find logic (§7 item 8 of the work-package plan).
 * - [findNext]/[findPrevious] move through the `FindModel`, which selects through the editor's executor, so the match
 *   is revealed like every action; while the query is empty they open the find bar instead.
 * - [goToDefinition] asks [definitions] for the symbol at the primary caret.
 */
internal class EditorUiRequests(
    private val text: EditableText,
    private val caret: CaretModel,
    private val find: FindModel,
    private val completion: CompletionSessionModel,
    private val overlays: EditorOverlayState,
    private val definitions: (offset: Int) -> Unit,
) : EditorUiPort {

    override fun openFind(replace: Boolean) {
        val selection = caret.primary.selection
        if (!selection.isEmpty) {
            val selected = text.snapshot.value.substring(selection)
            if ('\n' !in selected) find.setQuery(find.state.value.query.copy(text = selected))
        }
        overlays.openFind(if (replace) FindBarMode.REPLACE else FindBarMode.FIND)
    }

    override fun openGoToLine() {
        overlays.openGoToLine()
    }

    override fun findNext() {
        if (hasQuery()) find.next() else overlays.openFind(FindBarMode.FIND)
    }

    override fun findPrevious() {
        if (hasQuery()) find.previous() else overlays.openFind(FindBarMode.FIND)
    }

    override fun triggerCompletion() {
        completion.open(CompletionTrigger.EXPLICIT)
    }

    override fun goToDefinition() {
        definitions(caret.primary.position)
    }

    private fun hasQuery(): Boolean = find.state.value.query.text.isNotEmpty()
}
