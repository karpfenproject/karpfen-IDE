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
import io.karpfen.ide.notation.diagram.text.CellFrame
import io.karpfen.ide.notation.diagram.text.TextBlocks
import io.karpfen.ide.notation.diagram.text.WovenEdit
import io.karpfen.ide.notation.diagram.text.WovenText

/**
 * A piece of code that a diagram shows folded up, such as the entry actions of a state or the condition of a
 * transition. The diagram draws a small marker for it, and a click on the marker opens the code to be edited. So the
 * structure of a model is edited as a drawing and its code as text, both in the same view.
 *
 * One rule tells what saving a code writes into the text the cell was made from. [save] gives that as a change, and
 * [frame] gives the whole text it writes together with the mapping of positions between the code and that text, so
 * the language can see the code of an open cell in its place in the file.
 *
 * @property key identifies the cell within its diagram.
 * @property title what the code is, in words, as the head of the opened cell says it.
 * @property word the word drawn behind the marker, or empty for a marker that stands alone.
 * @property box where the marker and its word are drawn. A click in it opens the cell.
 * @property code the code as the user edits it, without the braces around it and without the indentation its lines
 *   share.
 */
public class DiagramCell internal constructor(
    public val key: String,
    public val title: String,
    public val word: String,
    public val box: DiagramBox,
    public val code: String,
    private val source: String,
    private val write: (String) -> CellWrite?,
) {

    /** The change that writes [code] back into the text, or null if nothing changes. */
    public fun save(code: String): DiagramChange? {
        val edit = write(code)?.edit ?: return null
        return if (source.substring(edit.range.start, edit.range.end) == edit.newText) null else DiagramChange(listOf(edit))
    }

    /**
     * The text of the file as it would be if [code] were saved now, with the mapping of positions between [code] and
     * that text. It is null if saving [code] leaves no place for it in the text, as when a blank code takes a tick
     * limit or a condition out, or when saving it changes nothing because there is no block to write it into.
     */
    public fun frame(code: String): CellFrame? {
        val written = write(code) ?: return null
        val placed = written.code ?: return null
        return CellFrame.of(source, code, written.edit.range, placed)
    }
}

/**
 * What saving one code of a [DiagramCell] writes into the text: one edit, and if the code stands in its new text, the
 * written text that knows where.
 *
 * @property edit the edit of the text.
 * @property code the new text of [edit] with the places of the code in it, or null if the edit takes the code out.
 */
internal class CellWrite private constructor(val edit: TextEdit, val code: WovenText?) {

    companion object {
        /** The edit that puts [written] in the place of [range]. */
        fun placing(range: TextRange, written: WovenText): CellWrite = CellWrite(TextEdit(range, written.text), written)

        /** The edit [woven], whose new text holds the code. */
        fun placing(woven: WovenEdit): CellWrite = placing(woven.range, woven.written)

        /** The edit [edit], which takes the code out of the text. */
        fun removing(edit: TextEdit): CellWrite = CellWrite(edit, null)
    }
}

/** Makes the code cells that show a whole construct of the text, such as a state or a relation with all it holds. */
public object ConstructCells {

    /**
     * The cell titled [title] with the key [key] that shows the construct at [range] of [source] as it stands in the
     * text, without the indentation of its first line. Saving puts the edited text in its place. Saving nothing takes
     * the construct out of the text.
     */
    public fun of(key: String, title: String, source: String, range: TextRange): DiagramCell =
        DiagramCell(key, title, "", DiagramBox(0f, 0f, 0f, 0f), TextBlocks.lifted(source, range), source) { code ->
            if (code.isBlank()) {
                CellWrite.removing(TextBlocks.removal(source, range))
            } else {
                CellWrite.placing(range, TextBlocks.placed(code, TextBlocks.indentOf(source, range.start)))
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
