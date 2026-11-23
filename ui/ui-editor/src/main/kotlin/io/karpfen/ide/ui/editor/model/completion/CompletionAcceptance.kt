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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * What accepting a completion item does, in the coordinates of the text it is accepted in. It is an immutable value
 * that [CompletionSession.acceptance] computes and the completion model carries out.
 *
 * @property edit the replacement of the item's replace range by its insert text, indented for the line it starts on.
 * @property caretAfter the caret after the edit. It selects the first placeholder of a template, and otherwise it stands
 *   at the item's caret offset or at the end of the insertion.
 * @property placeholders the placeholders of the inserted text in visiting order, as ranges of the text after the edit.
 * @property end where the caret goes after the last placeholder, as an offset of the text after the edit.
 */
internal data class CompletionAcceptance(
    val edit: TextEdit,
    val caretAfter: CaretMemento,
    val placeholders: List<TextRange>,
    val end: Int,
) {
    /** The inserted text as a range of the text after the edit. */
    val inserted: TextRange get() = TextRange.ofLength(edit.range.start, edit.newText.length)

    /** Computes acceptances. */
    companion object {
        /**
         * The acceptance of [item] over [range] of [snapshot]. Every line of the insert text after the first gets the
         * indentation of the line that [range] starts on in front of its own, so a template of several lines nests at
         * any depth. The indentation is the run of spaces and tabs at the start of that line, up to [range] at most.
         * The text uses `\n` as its line break, as every text of the editor does, and the inserted lines keep it.
         */
        fun of(item: CompletionItem, range: TextRange, snapshot: TextSnapshot): CompletionAcceptance {
            val indent = indentationAt(snapshot, range.start)
            val text = indented(item.insertText, indent)
            val shift = { offset: Int -> range.start + offset + indent.length * lineBreaksBefore(item.insertText, offset) }
            val placeholders = item.placeholders.map { TextRange(shift(it.start), shift(it.end)) }
            val end = shift(item.caretOffsetInInsert ?: item.insertText.length)
            val caret = placeholders.firstOrNull()?.let { CaretMemento(it.start, it.end) } ?: CaretMemento.at(end)
            return CompletionAcceptance(TextEdit(range, text), caret, placeholders, end)
        }

        /** The number of line breaks in [text] before [offset]. */
        private fun lineBreaksBefore(text: String, offset: Int): Int = (0 until offset).count { text[it] == '\n' }

        /** [text] with [indent] after each of its line breaks. */
        private fun indented(text: String, indent: String): String = if (indent.isEmpty()) text else text.replace("\n", "\n$indent")

        /** The spaces and tabs at the start of the line of [offset] in [snapshot], up to [offset]. */
        private fun indentationAt(snapshot: TextSnapshot, offset: Int): String {
            val text = snapshot.text
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            var end = lineStart
            while (end < offset && (text[end] == ' ' || text[end] == '\t')) end++
            return text.substring(lineStart, end)
        }
    }
}
