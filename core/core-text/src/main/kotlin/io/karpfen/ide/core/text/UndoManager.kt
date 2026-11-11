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
 * Undo and redo history of one [EditableText]. It is obtained from [EditableText.undo]. It records every change the
 * text applies and is driven from the same single writer thread.
 *
 * These rules decide what forms a step:
 * - Every [EditableText.apply] is one step, unless it coalesces with the previous step or runs inside [group].
 * - Consecutive [EditOrigin.Typing] insertions of a single character merge into one step if each is contiguous with
 *   the previous one and follows it within 1000 ms. The same holds for consecutive deletions of a single character.
 *   The pause is measured with the `TimeSource` of the text. A typed newline, or whitespace typed after a word
 *   character, starts a new step.
 * - [EditOrigin.Paste], [EditOrigin.Cut], [EditOrigin.Command], [EditOrigin.External] and
 *   [EditOrigin.Programmatic] never coalesce.
 * - Applying a new change clears the redo stack.
 *
 * A step made of several changes, such as a [group] or a coalesced typing run, keeps the `caretsBefore` of its first
 * change and the `caretsAfter` of its last change. Undo restores the former and redo the latter, as described at
 * [UndoResult.carets]. A missing memento stays missing, that is null. It is never taken from another change.
 */
public interface UndoManager {

    /** True if [undo] would change the text. */
    public val canUndo: StateFlow<Boolean>

    /** True if [redo] would change the text. */
    public val canRedo: StateFlow<Boolean>

    /**
     * True if and only if the current text state is the one recorded by the last [markSavePoint], reached through any
     * sequence of edits, undos and redos. It drives the dirty flag. False if no save point is reachable any more.
     */
    public val isAtSavePoint: StateFlow<Boolean>

    /**
     * Reverts the most recent step as one new change with origin [EditOrigin.Undo]. The text is restored byte for byte.
     * Returns null if there is nothing to undo.
     */
    public fun undo(): UndoResult?

    /**
     * Re-applies the most recently undone step as one new change with origin [EditOrigin.Redo]. Returns null if there
     * is nothing to redo.
     */
    public fun redo(): UndoResult?

    /**
     * Runs [block] and records every change it applies as one undo step named [name]. Groups nest, and only the
     * outermost group forms the step. A group that applies no change records nothing. The group is closed even if
     * [block] throws.
     */
    public fun <T> group(name: String, block: () -> T): T

    /**
     * Ends the current coalescing run, so the next change starts a new step. The editor's caret model calls it for
     * every explicit caret move, such as arrow keys, clicks and selections, so typing at another place is a new step.
     */
    public fun breakCoalescing()

    /**
     * Records the current state as the saved state, so [isAtSavePoint] becomes true. It is the same as
     * `markSavePoint(checkpoint())`.
     */
    public fun markSavePoint()

    /**
     * Ends the coalescing run and returns the identity of the current text state. Ending the run keeps the current
     * state reachable through undo and redo. The identity can be recorded as the saved state later with
     * [markSavePoint]. A save running in the background needs this, because the text it writes is the text of this
     * moment, not of the moment the write ends.
     */
    public fun checkpoint(): HistoryState

    /**
     * Records [state], a [checkpoint] of this manager, as the saved state, even if newer changes were applied since.
     * [isAtSavePoint] is true whenever undo and redo lead back to it. It stays false if the state can no longer be
     * reached, because its step lay on a discarded redo branch or a group amended it. A state of another manager is
     * never reached.
     */
    public fun markSavePoint(state: HistoryState)

    /**
     * Makes no text state the saved state. [isAtSavePoint] stays false through any edits, undos and redos until the
     * next [markSavePoint]. It is meant for a file whose content no state of the history matches any more, because the
     * file changed or vanished on disk while the buffer was kept.
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
