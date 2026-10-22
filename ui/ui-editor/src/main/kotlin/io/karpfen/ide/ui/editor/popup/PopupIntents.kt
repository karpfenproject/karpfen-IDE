package io.karpfen.ide.ui.editor.popup

import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.state.EditorPopupModel

/**
 * What the popups' gestures do to the editor (composables contain no logic): each intent translates one gesture into
 * calls on the host's [EditorPopupModel] and decides where keyboard focus goes afterwards — popups that own focus (find
 * bar, go-to-line, context menu) hand it back to the text area with [EditorPopupModel.requestFocus] when they are
 * closed by the user. Compose-free and stateless; one instance per editor.
 *
 * Editing never happens here: find, replace and completion acceptance are the models' operations, which run their
 * `EditorAction`s through the editor's executor (so the editor reveals the caret afterwards); context-menu entries run
 * the registered action of their id.
 *
 * The intents are grouped by popup, in this order: the find/replace bar, the completion list, the go-to-line popup, the
 * hover tooltip and the context menu.
 */
internal class PopupIntents(private val model: EditorPopupModel) {

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

    /** The replacement field's text changed to [text]: the model keeps it. */
    fun replacementChanged(text: String) = model.find.setReplacement(text)

    /** Enter in the replacement field or "Replace": replaces the current match by the model's replacement, moves on. */
    fun replace() = model.find.replaceCurrent(model.find.replacement.value)

    /** "Replace All": replaces every match by the model's replacement (one undo step). */
    fun replaceAll() = model.find.replaceAll(model.find.replacement.value)

    /** Escape or the close button: closes the bar (the query stays) and returns focus to the text area. */
    fun closeFind() {
        model.overlays.closeFind()
        model.requestFocus()
    }

    /** A click on row [index] highlights it (the text area keeps focus: clicks on the list never take it). */
    fun selectCompletion(index: Int) = model.completion.select(index)

    /** A double click accepts the highlighted item. */
    fun acceptCompletion() {
        model.completion.accept()
    }

    /**
     * The open completion list cannot be shown (the caret is scrolled out of view): closes the session, so the keys it
     * would take (Enter, Tab, arrows) reach the editor again.
     */
    fun completionHidden() = model.completion.close()

    /** Enter or "Go" with a valid query; the model moves the caret, closes the popup and requests focus. */
    fun goToLine(query: GoToLineQuery) = model.goToLine(query)

    /** Escape: closes the popup and returns focus to the text area. */
    fun cancelGoToLine() {
        model.overlays.closeGoToLine()
        model.requestFocus()
    }

    /** The popup lost focus to another component (e.g. a click into the text area or a tool window): closes it only. */
    fun dismissGoToLine() = model.overlays.closeGoToLine()

    /** A click on the tooltip hides it. */
    fun dismissHover() = model.overlays.hideHover()

    /** The pointer moved onto ([over] true) or off the tooltip: the editor keeps it while the pointer is on it. */
    fun pointerOverTooltip(over: Boolean) = model.pointerOverTooltip(over)

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
