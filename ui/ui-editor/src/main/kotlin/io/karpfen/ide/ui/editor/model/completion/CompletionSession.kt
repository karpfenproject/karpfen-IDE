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
import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret

/**
 * One open completion session. It holds the source's [items], computed for text [version] at the request [offset].
 * It also holds the session range from [start] to [end] in that version, which is the replace range of the first item
 * extended to the request offset. A session is immutable. The [CompletionSessionModel] keeps the current one.
 *
 * The session follows the text without a new request. Its bounds are anchors mapped with `EditableText.mapOffset`, the
 * point mapping used for carets and anchors. The start maps with `Bias.LEFT` and the end with `Bias.RIGHT`, because
 * text typed at either bound belongs to the word being completed. A decoration range mapped with `mapRange` would
 * shrink instead. The replace range of an item is mapped the same way when the item is accepted.
 *
 * Only the word being completed belongs to the session. The session ends as soon as anything other than identifier
 * characters lies between the request offset and the caret. This is [CompletionTriggers.stillHolds], the same rule an
 * outdated request follows. So a space, a bracket or a line break typed after a keyword closes the list. The next Enter
 * or Tab then belongs to the editor again and never accepts a snippet such as `DO { }` over the text typed since. This
 * matters because labels may contain spaces and brackets, and prefix matching alone would keep such items in the list.
 */
internal class CompletionSession private constructor(
    private val items: List<CompletionItem>,
    private val version: Long,
    private val offset: Int,
    private val start: Int,
    private val end: Int,
) {

    /**
     * Returns the visible state for the current [text] and [caret]. While the filter text is unchanged, the item that
     * was highlighted in [previous] stays highlighted. Returns null when the session must close. That happens when the
     * caret has a selection, when it left the range or the word being completed, when nothing matches, or when the
     * history no longer reaches [version].
     */
    fun uiState(text: EditableText, caret: Caret, previous: CompletionUiState?): CompletionUiState? {
        val from = anchor(text, start, Bias.LEFT) ?: return null
        val to = anchor(text, end, Bias.RIGHT) ?: return null
        if (caret.hasSelection || caret.position !in from..to) return null
        if (!CompletionTriggers.stillHolds(text, version, offset, caret)) return null
        val filter = text.snapshot.value.text.substring(from, caret.position)
        val ranked = CompletionRanking.rank(items, filter)
        if (ranked.isEmpty()) return null
        val kept = previous?.takeIf { it.filterText == filter }?.let { ranked.indexOf(it.selected) } ?: -1
        return CompletionUiState(ranked, if (kept >= 0) kept else 0, TextRange(from, to), filter)
    }

    /**
     * Returns what accepting [item] does in the current [text] with the caret at [caret]. The first value is the edit.
     * It replaces the item's replace range, mapped into the current text and extended to the caret, with the item's
     * insert text. The second value is the caret offset after the edit. That is `caretOffsetInInsert` if the item has
     * one, and the end of the insertion otherwise. Returns null if the history no longer reaches [version].
     */
    fun acceptance(text: EditableText, caret: Int, item: CompletionItem): Pair<TextEdit, Int>? {
        val from = anchor(text, item.replaceRange.start, Bias.LEFT) ?: return null
        val to = anchor(text, item.replaceRange.end, Bias.RIGHT) ?: return null
        val range = TextRange(minOf(from, caret), maxOf(to, caret))
        return TextEdit(range, item.insertText) to range.start + (item.caretOffsetInInsert ?: item.insertText.length)
    }

    /**
     * Maps [offset] from the session's version into the current [text] with [bias]. Returns null if the history no
     * longer reaches the session's version.
     */
    private fun anchor(text: EditableText, offset: Int, bias: Bias): Int? {
        val current = text.snapshot.value.version
        return if (version > current) null else text.mapOffset(offset, version, current, bias)
    }

    /** Creates sessions from the result of a source. */
    companion object {
        /** Creates the session of [items], which were computed for [snapshot] at [offset]. [items] must not be empty. */
        fun of(items: List<CompletionItem>, snapshot: TextSnapshot, offset: Int): CompletionSession {
            require(items.isNotEmpty()) { "a session has items" }
            val range = items.first().replaceRange
            return CompletionSession(items, snapshot.version, offset, minOf(range.start, offset), maxOf(range.end, offset))
        }
    }
}
