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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.editor.model.caret.WordBoundaries

/**
 * Decides what a deletion key removes at a caret without a selection. Each function returns a range of the current
 * text, or null when there is nothing to delete. The object holds pure functions and no state. [DeletionAction]
 * applies the result.
 */
internal object DeletionRules {

    /**
     * Returns what Backspace at [position] removes. If the caret sits between the two characters of a tracked
     * auto-closed pair, both characters go. Inside leading whitespace, the spaces back to the previous 4-column stop go,
     * at least one character. This is smart unindent. Otherwise the character before the caret goes, and a surrogate
     * pair goes as a whole.
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

    /** Returns what Delete at [position] removes: the character after the caret. A surrogate pair goes as a whole. */
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
