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
package io.karpfen.ide.core.text

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A change that may coalesce with the previous one into one undo step. It is a single character that was typed or
 * deleted, together with the time it was recorded. A character is one code point, so a surrogate pair counts as one
 * character.
 */
internal sealed interface Keystroke {

    /** When the keystroke was recorded. */
    val at: TimeMark

    /** [text], which is one code point, inserted at [offset]. */
    class Insertion(val offset: Int, val text: String, override val at: TimeMark) : Keystroke {
        /** The offset directly after the inserted character. */
        val end: Int get() = offset + text.length
    }

    /** One code point at [range] deleted with Backspace or Delete. */
    class Deletion(val range: TextRange, override val at: TimeMark) : Keystroke
}

/**
 * The coalescing rules of the undo history, as described at [UndoManager].
 *
 * Consecutive [EditOrigin.Typing] insertions of a single character merge if each one is contiguous with the previous
 * one and the pause between them is at most [window]. Consecutive deletions of a single character merge under the same
 * conditions. In a Backspace run each deletion ends where the previous one started. In a Delete run each deletion
 * starts where the previous one started. A typed newline, or whitespace typed after a word character, starts a new
 * step. No other origin ever coalesces.
 *
 * The class keeps no state apart from the injected [timeSource]. The undo manager keeps the previous keystroke.
 */
internal class TypingCoalescer(
    private val timeSource: TimeSource,
    private val window: Duration = EditableText.COALESCE_WINDOW_MS.milliseconds,
) {

    /** The [change] as a [Keystroke] recorded now. Returns null if it is not a single typed or deleted character. */
    fun keystrokeOf(change: TextChange): Keystroke? {
        if (change.origin != EditOrigin.Typing || change.edits.size != 1) return null
        val edit = change.edits.single()
        return when {
            edit.isInsertion && edit.newText.isOneCodePoint() ->
                Keystroke.Insertion(edit.range.start, edit.newText, timeSource.markNow())
            edit.isDeletion && change.before.substring(edit.range).isOneCodePoint() ->
                Keystroke.Deletion(edit.range, timeSource.markNow())
            else -> null
        }
    }

    /** True if [next] continues the run that ended with [previous], so both belong to one undo step. */
    fun continues(previous: Keystroke, next: Keystroke): Boolean {
        val pause = previous.at.elapsedNow() - next.at.elapsedNow()
        if (pause > window) return false
        return when {
            previous is Keystroke.Insertion && next is Keystroke.Insertion ->
                next.offset == previous.end && !startsNewStep(previous.text, next.text)
            previous is Keystroke.Deletion && next is Keystroke.Deletion ->
                next.range.end == previous.range.start || next.range.start == previous.range.start
            else -> false
        }
    }

    private fun startsNewStep(previous: String, next: String): Boolean =
        next == "\n" || (next.isWhitespace() && previous.codePointAt(0).isWordCharacter())

    private fun String.isOneCodePoint(): Boolean =
        isNotEmpty() && Character.charCount(codePointAt(0)) == length

    private fun String.isWhitespace(): Boolean = Character.isWhitespace(codePointAt(0))

    private fun Int.isWordCharacter(): Boolean = Character.isLetterOrDigit(this) || this == '_'.code
}
