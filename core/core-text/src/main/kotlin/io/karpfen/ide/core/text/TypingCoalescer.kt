package io.karpfen.ide.core.text

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A change that may coalesce with the previous one into one undo step: a single character (one code point, so a
 * surrogate pair counts as one character) typed or deleted, with the time it was recorded.
 */
internal sealed interface Keystroke {

    /** When the keystroke was recorded. */
    val at: TimeMark

    /** [text] (one code point) inserted at [offset]. */
    class Insertion(val offset: Int, val text: String, override val at: TimeMark) : Keystroke {
        /** The offset directly after the inserted character. */
        val end: Int get() = offset + text.length
    }

    /** One code point at [range] deleted (Backspace or Delete). */
    class Deletion(val range: TextRange, override val at: TimeMark) : Keystroke
}

/**
 * The coalescing rules of the undo history (spec §2, [UndoManager]): consecutive [EditOrigin.Typing] single-character
 * insertions contiguous with the previous one, or consecutive single-character deletions contiguous with the previous
 * one (Backspace runs end where the previous deletion started, Delete runs start where it started), merge if the pause
 * between them is at most [window]. A typed newline, or whitespace typed after a word character, starts a new step.
 * No other origin ever coalesces.
 *
 * Stateless apart from the injected [timeSource]; the undo manager keeps the previous keystroke.
 */
internal class TypingCoalescer(
    private val timeSource: TimeSource,
    private val window: Duration = EditableText.COALESCE_WINDOW_MS.milliseconds,
) {

    /** The [change] as a [Keystroke] recorded now, or null if it is not a single typed or deleted character. */
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
