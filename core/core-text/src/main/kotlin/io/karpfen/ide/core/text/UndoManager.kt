package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.StateFlow

/**
 * Undo/redo history of one [EditableText] (spec §2). Obtained from [EditableText.undo]; it records every change the
 * text applies and is driven from the same single writer thread.
 *
 * Steps and coalescing (MUST):
 * - Every [EditableText.apply] is one step unless it coalesces with the previous step or runs inside [group].
 * - Consecutive [EditOrigin.Typing] single-character insertions contiguous with the previous one, or consecutive
 *   single-character deletions contiguous with the previous one, within 1000 ms (measured with the text's
 *   `TimeSource`), merge into one step. A typed newline, or whitespace typed after a word character, starts a new step.
 * - [EditOrigin.Paste], [EditOrigin.Cut], [EditOrigin.Command], [EditOrigin.External] and
 *   [EditOrigin.Programmatic] never coalesce.
 * - Applying a new change clears the redo stack.
 *
 * Caret mementos of a step (MUST): a step made of several changes (a [group] or a coalesced typing run) keeps the
 * `caretsBefore` of its first change and the `caretsAfter` of its last change; undo restores the former, redo the
 * latter (see [UndoResult.carets]). A missing memento stays missing (null), it is not taken from another change.
 */
public interface UndoManager {

    /** True if [undo] would change the text. */
    public val canUndo: StateFlow<Boolean>

    /** True if [redo] would change the text. */
    public val canRedo: StateFlow<Boolean>

    /**
     * True iff the current text state is the one recorded by the last [markSavePoint], reached through any sequence of
     * edits, undos and redos (drives the dirty flag). False if no save point is reachable any more.
     */
    public val isAtSavePoint: StateFlow<Boolean>

    /**
     * Reverts the most recent step as one new change (origin [EditOrigin.Undo]); the text is restored byte-for-byte.
     * Returns null if there is nothing to undo.
     */
    public fun undo(): UndoResult?

    /** Re-applies the most recently undone step (origin [EditOrigin.Redo]); null if there is nothing to redo. */
    public fun redo(): UndoResult?

    /**
     * Runs [block] and records every change it applies as one undo step named [name]. Groups nest; only the outermost
     * group forms the step. A group that applies no change records nothing. The group is closed even if [block] throws.
     */
    public fun <T> group(name: String, block: () -> T): T

    /**
     * Ends the current coalescing run, so the next change starts a new step. The editor's caret model calls it for
     * every explicit caret move (arrow keys, clicks, selections), so typing at another place is a new step.
     */
    public fun breakCoalescing()

    /** Records the current state as the saved state ([isAtSavePoint] becomes true). */
    public fun markSavePoint()

    /**
     * Forgets the undo and redo history. The save point survives only if the current state is the save point.
     * Does not change the text.
     */
    public fun clear()
}
