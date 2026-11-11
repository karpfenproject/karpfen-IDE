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
 * Applies [edits] as exactly one change, which is one undo step. The edits use offsets of the current text. The change
 * records the current carets as `caretsBefore` and [caretsAfter] as `caretsAfter`. [caretsAfter] uses offsets of the
 * new text. After the change the carets are placed at [caretsAfter] through `CaretModel.restore`, which keeps the
 * current typing run going. If the edits change nothing, the function changes nothing and returns false.
 *
 * Editor actions change text only through this function. That way every action records its caret mementos, which is
 * the Memento pattern.
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
 * An edit that a model prepared and that runs through the editor's [EditorActionExecutor]. Examples are a find and
 * replace replacement and an accepted completion. This is the Command pattern. The action applies [edits], which were
 * computed for text [version], as one change with origin `Command(name)` and places the carets at [caretsAfter]. It is
 * not registered. Each use creates a new instance, identified by [id].
 *
 * If the text moved past [version] before the executor ran the action, it does nothing and returns false. Stale offsets
 * are never applied. If the edits change nothing, for example because the accepted text is already there, only the
 * carets are placed.
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
