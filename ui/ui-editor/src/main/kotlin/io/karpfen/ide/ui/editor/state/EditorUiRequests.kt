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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.action.EditorUiPort
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.completion.CompletionTrigger
import io.karpfen.ide.ui.editor.model.find.FindModel

/**
 * The [EditorUiPort] of the editor. It answers what actions ask of the UI with the editor's models and overlay state.
 *
 * [openFind] seeds the find query from a selection on one line before it opens the bar. It calls `FindModel.setQuery`
 * with only the text of the model's current query replaced, so the options stay. The selection stays the current
 * match. Seeding happens here, so the overlay state holds no find logic.
 *
 * [findNext] and [findPrevious] move through the `FindModel`. The model selects through the editor's executor, so the
 * match is revealed like after every action. While the model's query is empty, they open the find bar instead.
 *
 * [triggerCompletion] reveals the primary caret with [revealCaret] before it asks for completion there. The request
 * changes neither text nor carets, so the executor would reveal nothing. A list at an off-screen caret could not be
 * shown.
 *
 * [goToDefinition] asks [definitions] for the symbol at the primary caret.
 */
internal class EditorUiRequests(
    private val text: EditableText,
    private val caret: CaretModel,
    private val find: FindModel,
    private val completion: CompletionSessionModel,
    private val overlays: EditorOverlayState,
    private val revealCaret: () -> Unit,
    private val definitions: (offset: Int) -> Unit,
) : EditorUiPort {

    override fun openFind(replace: Boolean) {
        val selection = caret.primary.selection
        if (!selection.isEmpty) {
            val selected = text.snapshot.value.substring(selection)
            if ('\n' !in selected) find.setQuery(find.query.value.copy(text = selected))
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
        revealCaret()
        completion.open(CompletionTrigger.EXPLICIT)
    }

    override fun goToDefinition() {
        definitions(caret.primary.position)
    }

    /** Whether the find model has a query to move through. */
    private fun hasQuery(): Boolean = find.query.value.text.isNotEmpty()
}
