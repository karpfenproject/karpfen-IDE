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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.notation.diagram.layout.DiagramBox
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * A piece of code that a diagram shows folded up, such as the entry actions of a state or the condition of a
 * transition. The diagram draws a small marker for it, and a click on the marker opens the code to be edited. So the
 * structure of a model is edited as a drawing and its code as text, both in the same view.
 *
 * @property key identifies the cell within its diagram.
 * @property title what the code is, in words, as the head of the opened cell says it.
 * @property word the word drawn behind the marker, or empty for a marker that stands alone.
 * @property box where the marker and its word are drawn. A click in it opens the cell.
 * @property code the code as the user edits it, without the braces around it and without the indentation its lines
 *   share.
 * @property save gives the change that writes the edited code back into the text, or null if nothing changes.
 */
public class DiagramCell(
    public val key: String,
    public val title: String,
    public val word: String,
    public val box: DiagramBox,
    public val code: String,
    public val save: (String) -> DiagramChange?,
)

/** Makes the code cells that show a whole construct of the text, such as a state or a relation with all it holds. */
public object ConstructCells {

    /**
     * The cell titled [title] with the key [key] that shows the construct at [range] of [source] as it stands in the
     * text, without the indentation of its first line. Saving puts the edited text in its place. Saving nothing takes
     * the construct out of the text.
     */
    public fun of(key: String, title: String, source: String, range: TextRange): DiagramCell =
        DiagramCell(key, title, "", DiagramBox(0f, 0f, 0f, 0f), TextBlocks.lifted(source, range)) { code ->
            val placed = TextBlocks.placed(code, TextBlocks.indentOf(source, range.start))
            when {
                code.isBlank() -> DiagramChange(listOf(TextBlocks.removal(source, range)))
                placed == source.substring(range.start, range.end) -> null
                else -> DiagramChange(listOf(TextEdit(range, placed)))
            }
        }
}

/**
 * Tells which code cells one kind of diagram has.
 *
 * @param D the diagram, as it is derived from the text.
 * @param L the layout of that kind of diagram.
 */
public fun interface DiagramCells<D, L : DiagramLayout> {
    /** The code cells of [diagram], which is drawn as [layout] and was derived from the text [source]. */
    public fun of(source: String, diagram: D, layout: L): List<DiagramCell>

    public companion object {
        /** No code cells. */
        public fun <D, L : DiagramLayout> none(): DiagramCells<D, L> = DiagramCells { _, _, _ -> emptyList() }
    }
}
