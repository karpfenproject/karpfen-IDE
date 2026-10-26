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

import kotlinx.coroutines.flow.StateFlow

/**
 * Undo/redo history of one [EditableText]. Obtained from [EditableText.undo]; it records every change the
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

    /** Records the current state as the saved state ([isAtSavePoint] becomes true); `markSavePoint(checkpoint())`. */
    public fun markSavePoint()

    /**
     * Ends the coalescing run (so the current state stays reachable through undo and redo) and returns the identity of
     * the current text state, to be recorded as the saved state later with [markSavePoint] — when the text written by a
     * save running in the background is the one of this moment, not of the moment the write ends.
     */
    public fun checkpoint(): HistoryState

    /**
     * Records [state], a [checkpoint] of this manager, as the saved state, also if newer changes were applied since:
     * [isAtSavePoint] is true whenever undo and redo lead back to it, and stays false if it can no longer be reached
     * (its step lay on a discarded redo branch, or a group amended it). A state of another manager is never reached.
     */
    public fun markSavePoint(state: HistoryState)

    /**
     * Makes no text state the saved state: [isAtSavePoint] stays false through any edits, undos and redos until the
     * next [markSavePoint]. For a file whose content no state of the history matches any more (it changed or vanished
     * on disk while the buffer was kept).
     */
    public fun invalidateSavePoint()

    /**
     * Forgets the undo and redo history. The save point survives only if the current state is the save point.
     * Does not change the text.
     */
    public fun clear()
}

/**
 * Opaque identity of one text state of an [UndoManager]'s history, taken with [UndoManager.checkpoint].
 *
 * @property identity the history's identity object of that state, compared by reference.
 */
public class HistoryState internal constructor(internal val identity: Any)
