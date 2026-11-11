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

import io.karpfen.ide.ui.editor.model.caret.CaretMovement

/**
 * Moves all carets by [movement]. If [extendSelection] is true, the selection grows with the move. Page moves use
 * `context.pageLines` as the page size.
 */
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
