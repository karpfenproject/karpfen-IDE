package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries

/**
 * What a deletion key removes at a caret without selection (spec §7.1), as a range of the current text, or null when
 * there is nothing to delete. Pure functions without state; [DeletionAction] applies the result.
 */
internal object DeletionRules {

    /**
     * Backspace at [position]: a tracked auto-closed pair around the caret (both characters); inside leading
     * whitespace the spaces back to the previous 4-column stop (smart unindent, at least one character); else the
     * character before the caret (a surrogate pair as a whole).
     */
    fun backspace(snapshot: TextSnapshot, position: Int, autoPairs: AutoClosedPairs): TextRange? {
        if (position <= 0) return null
        autoPairs.pairAround(position)?.let { return it }
        smartUnindent(snapshot, position)?.let { return it }
        val text = snapshot.text
        val start = if (position >= 2 && text[position - 1].isLowSurrogate() && text[position - 2].isHighSurrogate()) {
            position - 2
        } else {
            position - 1
        }
        return TextRange(start, position)
    }

    /** Delete at [position]: the character after the caret (a surrogate pair as a whole). */
    fun forward(snapshot: TextSnapshot, position: Int): TextRange? {
        val text = snapshot.text
        if (position >= text.length) return null
        val surrogatePair = position + 1 < text.length && text[position].isHighSurrogate() && text[position + 1].isLowSurrogate()
        return TextRange(position, if (surrogatePair) position + 2 else position + 1)
    }

    /** Delete word left: back to the previous word start. */
    fun wordLeft(snapshot: TextSnapshot, position: Int): TextRange? =
        TextRange(WordBoundaries.previousWordStart(snapshot.text, position), position).takeUnless { it.isEmpty }

    /** Delete word right: up to the next word end. */
    fun wordRight(snapshot: TextSnapshot, position: Int): TextRange? =
        TextRange(position, WordBoundaries.nextWordEnd(snapshot.text, position)).takeUnless { it.isEmpty }

    /** The spaces left of [position] back to the previous indentation stop, if [position] is in leading whitespace. */
    private fun smartUnindent(snapshot: TextSnapshot, position: Int): TextRange? {
        val lineStart = snapshot.lineIndex.lineStart(snapshot.lineIndex.lineOf(position))
        val prefix = snapshot.text.substring(lineStart, position)
        if (prefix.isEmpty() || prefix.last() != ' ' || prefix.any { !Indentation.isIndentChar(it) }) return null
        val column = Indentation.stops.displayColumn(prefix, prefix.length)
        val target = (column - 1) / Indentation.SIZE * Indentation.SIZE
        var start = position
        while (start > lineStart && snapshot[start - 1] == ' ' &&
            Indentation.stops.displayColumn(prefix, start - lineStart) > target
        ) {
            start--
        }
        return TextRange(start, position)
    }
}
