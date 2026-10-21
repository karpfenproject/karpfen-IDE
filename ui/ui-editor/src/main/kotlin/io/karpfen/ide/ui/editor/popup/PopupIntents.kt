package io.karpfen.ide.ui.editor.popup

import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.state.EditorPopupModel

/**
 * What the popups' gestures do to the editor (spec §1a: composables contain no logic): each intent translates one
 * gesture into calls on the host's [EditorPopupModel] and decides where keyboard focus goes afterwards — popups that
 * own focus (find bar, go-to-line, context menu) hand it back to the text area with [EditorPopupModel.requestFocus] when
 * they are closed by the user. Compose-free and stateless; one instance per editor.
 *
 * Editing never happens here: find, replace and completion acceptance are the models' operations, which run their
 * `EditorAction`s through the editor's executor (so the editor reveals the caret afterwards); context-menu entries run
 * the registered action of their id.
 */
internal class PopupIntents(private val model: EditorPopupModel) {

    // --- find/replace bar ------------------------------------------------------------------------------------------

    /**
     * The query field's text changed to [text] (incremental search): the model's query with that text (the model selects
     * the first match through the executor).
     */
    fun findTextChanged(text: String) = model.find.setQuery(model.find.query.value.copy(text = text))

    /** A toggle was switched: the model's query with [option] switched. */
    fun toggleFindOption(option: FindOption) = model.find.setQuery(option.toggle(model.find.query.value))

    /** Enter in the query field or the "next" button. */
    fun findNext() = model.find.next()

    /** Shift+Enter in the query field or the "previous" button. */
    fun findPrevious() = model.find.previous()

    /** Replaces the current match by [replacement] and moves to the next one. */
    fun replace(replacement: String) = model.find.replaceCurrent(replacement)

    /** Replaces every match by [replacement] (one undo step). */
    fun replaceAll(replacement: String) {
        model.find.replaceAll(replacement)
    }

    /** Escape or the close button: closes the bar (the query stays) and returns focus to the text area. */
    fun closeFind() {
        model.overlays.closeFind()
        model.requestFocus()
    }

    // --- completion ------------------------------------------------------------------------------------------------

    /** A click on row [index] highlights it (the text area keeps focus: clicks on the list never take it). */
    fun selectCompletion(index: Int) = model.completion.select(index)

    /** A double click accepts the highlighted item. */
    fun acceptCompletion() {
        model.completion.accept()
    }

    // --- go to line ------------------------------------------------------------------------------------------------

    /** Enter or "Go" with a valid query; the model moves the caret, closes the popup and requests focus. */
    fun goToLine(query: GoToLineQuery) = model.goToLine(query)

    /** Escape: closes the popup and returns focus to the text area. */
    fun cancelGoToLine() {
        model.overlays.closeGoToLine()
        model.requestFocus()
    }

    /** The popup lost focus to another component (e.g. a click into the text area or a tool window): closes it only. */
    fun dismissGoToLine() = model.overlays.closeGoToLine()

    // --- hover -----------------------------------------------------------------------------------------------------

    /** A click on the tooltip hides it. */
    fun dismissHover() = model.overlays.hideHover()

    // --- context menu ----------------------------------------------------------------------------------------------

    /** Choosing the entry of [actionId]: closes the menu, runs the action by id and returns focus to the text area. */
    fun runContextAction(actionId: String) {
        model.overlays.closeContextMenu()
        model.execute(actionId)
        model.requestFocus()
    }

    /** Escape or a click outside the menu: closes it and returns focus to the text area. */
    fun dismissContextMenu() {
        model.overlays.closeContextMenu()
        model.requestFocus()
    }
}
