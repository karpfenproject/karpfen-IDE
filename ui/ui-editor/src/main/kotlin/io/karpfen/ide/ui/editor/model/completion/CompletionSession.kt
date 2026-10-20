package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.text.Bias
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.Caret

/**
 * One open completion session: the source's [items], computed for text [version], and the session range
 * [start]..[end] in that version — the first item's replace range extended to the request offset. Immutable; the
 * [CompletionSessionModel] keeps the current one.
 *
 * The session follows the text without a new request: its bounds are anchors mapped with `EditableText.mapOffset`
 * (the point mapping of carets and anchors), the start with `Bias.LEFT` and the end with `Bias.RIGHT`, because text
 * typed at either bound belongs to the word being completed (a decoration range, mapped with `mapRange`, would shrink
 * instead). An item's own replace range is mapped the same way when it is accepted.
 */
internal class CompletionSession private constructor(
    private val items: List<CompletionItem>,
    private val version: Long,
    private val start: Int,
    private val end: Int,
) {

    /**
     * The visible state for the current [text] and [caret], keeping the [previous] highlighted item while the filter
     * text is unchanged; null when the session must close: the caret has a selection or left the range, nothing
     * matches, or the history no longer reaches [version].
     */
    fun uiState(text: EditableText, caret: Caret, previous: CompletionUiState?): CompletionUiState? {
        val from = anchor(text, start, Bias.LEFT) ?: return null
        val to = anchor(text, end, Bias.RIGHT) ?: return null
        if (caret.hasSelection || caret.position !in from..to) return null
        val filter = text.snapshot.value.text.substring(from, caret.position)
        val ranked = CompletionRanking.rank(items, filter)
        if (ranked.isEmpty()) return null
        val kept = previous?.takeIf { it.filterText == filter }?.let { ranked.indexOf(it.selected) } ?: -1
        return CompletionUiState(ranked, if (kept >= 0) kept else 0, TextRange(from, to), filter)
    }

    /**
     * What accepting [item] does in the current [text] with the caret at [caret]: the edit replacing the item's
     * replace range, mapped into the current text and extended to the caret, by its insert text; and the caret offset
     * after it (`caretOffsetInInsert`, or the end of the insertion). Null if the history no longer reaches [version].
     */
    fun acceptance(text: EditableText, caret: Int, item: CompletionItem): Pair<TextEdit, Int>? {
        val from = anchor(text, item.replaceRange.start, Bias.LEFT) ?: return null
        val to = anchor(text, item.replaceRange.end, Bias.RIGHT) ?: return null
        val range = TextRange(minOf(from, caret), maxOf(to, caret))
        return TextEdit(range, item.insertText) to range.start + (item.caretOffsetInInsert ?: item.insertText.length)
    }

    private fun anchor(text: EditableText, offset: Int, bias: Bias): Int? {
        val current = text.snapshot.value.version
        return if (version > current) null else text.mapOffset(offset, version, current, bias)
    }

    companion object {
        /** The session of non-empty [items], computed for [snapshot] at [offset]. */
        fun of(items: List<CompletionItem>, snapshot: TextSnapshot, offset: Int): CompletionSession {
            require(items.isNotEmpty()) { "a session has items" }
            val range = items.first().replaceRange
            return CompletionSession(items, snapshot.version, minOf(range.start, offset), maxOf(range.end, offset))
        }
    }
}
