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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit

/**
 * Applies [edits] (coordinates of the current text) as exactly one change — one undo step — with the current carets as
 * `caretsBefore` and [caretsAfter] (coordinates of the new text) as `caretsAfter`, then places the carets there
 * (`CaretModel.restore`, which keeps the typing run going). Returns false, changing nothing, if the edits are no-ops.
 *
 * The one way the editor actions change text, so every action records its caret mementos (Memento).
 */
internal fun EditorActionContext.applyEdits(
    edits: List<TextEdit>,
    origin: EditOrigin,
    caretsAfter: List<CaretMemento>,
): Boolean {
    text.apply(edits, origin, caret.mementos(), caretsAfter) ?: return false
    caret.restore(caretsAfter)
    return true
}

/** [applyEdits] with one [edit] and one caret at [caretAfter]. */
internal fun EditorActionContext.applyEdit(edit: TextEdit, origin: EditOrigin, caretAfter: Int): Boolean =
    applyEdits(listOf(edit), origin, listOf(CaretMemento.at(caretAfter)))

/**
 * An edit prepared by a model — a find/replace replacement, an accepted completion — and run through the editor's
 * [EditorActionExecutor] (Command pattern): applies [edits], computed for text [version], as one change with origin
 * `Command(name)` and the carets [caretsAfter]. Not registered (one instance per use, identified by [id]).
 *
 * Does nothing and returns false if the text moved past [version] before the executor ran it (stale offsets are never
 * applied). If the edits change nothing (e.g. the accepted text is already there), only the carets are placed.
 */
internal class CommandEditAction(
    override val id: String,
    private val name: String,
    private val version: Long,
    private val edits: List<TextEdit>,
    private val caretsAfter: List<CaretMemento>,
) : EditorAction {
    override fun execute(context: EditorActionContext): Boolean {
        if (context.text.snapshot.value.version != version) return false
        if (context.applyEdits(edits, EditOrigin.Command(name), caretsAfter)) return true
        val before = context.caret.mementos()
        context.caret.restore(caretsAfter)
        return context.caret.mementos() != before
    }
}
