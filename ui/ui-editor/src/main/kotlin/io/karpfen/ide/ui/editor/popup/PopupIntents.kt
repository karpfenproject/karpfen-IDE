/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.editor.popup

import io.karpfen.ide.ui.editor.model.navigation.GoToLineQuery
import io.karpfen.ide.ui.editor.state.EditorPopupModel

/**
 * What the gestures in the popups do to the editor. Composables contain no logic, so it lives here. Each intent turns
 * one gesture into calls on the host's [EditorPopupModel] and decides where keyboard focus goes afterwards. Popups that
 * own focus give it back to the text area with [EditorPopupModel.requestFocus] when the user closes them. These are
 * the find bar, go-to-line and the context menu. The class does not need Compose and has no state. There is one
 * instance per editor.
 *
 * Editing never happens here. Find, replace and completion acceptance are operations of the models. They run their
 * `EditorAction`s through the editor's executor, so the editor reveals the caret afterwards. Context menu entries run
 * the registered action of their id.
 *
 * The intents are grouped by popup in this order: the find and replace bar, the completion list, the go-to-line popup,
 * the hover tooltip and the context menu.
 */
internal class PopupIntents(private val model: EditorPopupModel) {

    /**
     * The text of the query field changed to [text]. This is incremental search. The model gets its current query with
     * the new text, and it selects the first match through the executor.
     */
    fun findTextChanged(text: String) = model.find.setQuery(model.find.query.value.copy(text = text))

    /** A toggle was switched. The model gets its current query with [option] switched. */
    fun toggleFindOption(option: FindOption) = model.find.setQuery(option.toggle(model.find.query.value))

    /** Enter in the query field, or the "next" button. */
    fun findNext() = model.find.next()

    /** Shift+Enter in the query field, or the "previous" button. */
    fun findPrevious() = model.find.previous()

    /** The text of the replacement field changed to [text]. The model keeps it. */
    fun replacementChanged(text: String) = model.find.setReplacement(text)

    /** Enter in the replacement field, or "Replace". It replaces the current match with the model's replacement and moves on. */
    fun replace() = model.find.replaceCurrent(model.find.replacement.value)

    /** "Replace All". It replaces every match with the model's replacement as one undo step. */
    fun replaceAll() = model.find.replaceAll(model.find.replacement.value)

    /** Escape or the close button. It closes the bar and returns focus to the text area. The query stays. */
    fun closeFind() {
        model.overlays.closeFind()
        model.requestFocus()
    }

    /** A click on row [index] highlights it. The text area keeps focus, because clicks on the list never take it. */
    fun selectCompletion(index: Int) = model.completion.select(index)

    /** A double click accepts the highlighted item. */
    fun acceptCompletion() {
        model.completion.accept()
    }

    /**
     * The open completion list cannot be shown, because the caret is scrolled out of view. This closes the session, so
     * the keys it would take, such as Enter, Tab and the arrows, reach the editor again.
     */
    fun completionHidden() = model.completion.close()

    /** Enter or "Go" with a valid query. The model moves the caret, closes the popup and requests focus. */
    fun goToLine(query: GoToLineQuery) = model.goToLine(query)

    /** Escape. It closes the popup and returns focus to the text area. */
    fun cancelGoToLine() {
        model.overlays.closeGoToLine()
        model.requestFocus()
    }

    /**
     * The popup lost focus to another component, for example through a click into the text area or a tool window. It
     * only closes the popup.
     */
    fun dismissGoToLine() = model.overlays.closeGoToLine()

    /** A click on the tooltip hides it. */
    fun dismissHover() = model.overlays.hideHover()

    /** The pointer moved onto the tooltip when [over] is true, or off it. The editor keeps the tooltip while the pointer is on it. */
    fun pointerOverTooltip(over: Boolean) = model.pointerOverTooltip(over)

    /** The entry of [actionId] was chosen. It closes the menu, runs the action by id and returns focus to the text area. */
    fun runContextAction(actionId: String) {
        model.overlays.closeContextMenu()
        model.execute(actionId)
        model.requestFocus()
    }

    /** Escape or a click outside the menu. It closes the menu and returns focus to the text area. */
    fun dismissContextMenu() {
        model.overlays.closeContextMenu()
        model.requestFocus()
    }
}
