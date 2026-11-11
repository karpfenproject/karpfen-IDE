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

import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.ui.editor.model.caret.Caret

/**
 * Computes where offsets land after an action's own [edits]. The edits must not overlap and use offsets of the text
 * before them. An action that edits several places, such as indent, toggle comment or replace all, uses this class to
 * compute its `caretsAfter` before it applies the edits. It has to, because `EditableText.apply` records the caret
 * mementos together with the change. Carets after changes made elsewhere are not computed here. The `CaretModel` maps
 * those through the text's history.
 *
 * An offset before an edit stays where it is. An offset after an edit shifts by the edit's length delta. An offset
 * inside a replaced range moves to the start of the replacement for [Bias.LEFT] or to its end for [Bias.RIGHT]. At an
 * insertion point the bias decides in the same way.
 */
internal class EditShift(edits: List<TextEdit>) {

    private val sorted = edits.sortedBy { it.range.start }

    /** Returns where [offset] lands after the edits. [bias] decides for offsets at an insertion point or inside a replaced range. */
    fun offset(offset: Int, bias: Bias): Int {
        var delta = 0
        for (edit in sorted) {
            val range = edit.range
            when {
                range.isEmpty && (range.start < offset || (range.start == offset && bias == Bias.RIGHT)) ->
                    delta += edit.newText.length
                range.isEmpty -> break
                range.end <= offset -> delta += edit.lengthDelta
                range.start < offset ->
                    return range.start + delta + if (bias == Bias.RIGHT) edit.newText.length else 0
                else -> break
            }
        }
        return offset + delta
    }

    /**
     * Returns [caret] after the edits. A selection keeps its direction and grows over text inserted at its bounds. Its
     * start uses [Bias.LEFT] and its end [Bias.RIGHT], so indenting or commenting whole lines keeps them selected. A
     * caret without a selection moves along with inserted text, using [Bias.RIGHT].
     */
    fun caret(caret: Caret): CaretMemento {
        if (!caret.hasSelection) return CaretMemento.at(offset(caret.position, Bias.RIGHT))
        val start = offset(caret.selection.start, Bias.LEFT)
        val end = offset(caret.selection.end, Bias.RIGHT)
        return if (caret.anchor <= caret.position) CaretMemento(start, end) else CaretMemento(end, start)
    }
}
