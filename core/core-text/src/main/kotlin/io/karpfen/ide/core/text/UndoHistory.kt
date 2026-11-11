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

/**
 * The undo and redo stacks of one text and its save point.
 *
 * Every text state the history can reach has an identity. It is the step that led to the state, which is the top of
 * the undo stack. With an empty undo stack it is the identity of the base state. The save point is such an identity,
 * so [isAtSavePoint] is a reference comparison. A state that can no longer be reached keeps an identity that nobody
 * will ever have again. That happens when its step was amended or lay on a discarded redo branch.
 *
 * This is a plain data structure of the [DefaultUndoManager]. It is not thread-safe.
 */
internal class UndoHistory {

    private val undoSteps = ArrayList<UndoStep>()
    private val redoSteps = ArrayList<UndoStep>()

    /** Identity of the state below the oldest undo step. */
    private var baseState: Any = Any()

    /** Identity of the saved state. The initial state is the saved state. */
    private var savedState: Any = baseState

    /** Identity of the current state. */
    val currentState: Any get() = undoSteps.lastOrNull() ?: baseState

    /** True if there is a step to undo. */
    val canUndo: Boolean get() = undoSteps.isNotEmpty()

    /** True if there is a step to redo. */
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    /** True if the current state is the saved state. */
    val isAtSavePoint: Boolean get() = currentState === savedState

    /** The step an undo reverts, which is the newest step, or null. */
    val undoTop: UndoStep? get() = undoSteps.lastOrNull()

    /** The step a redo re-applies, which is the newest undone step, or null. */
    val redoTop: UndoStep? get() = redoSteps.lastOrNull()

    /** Records [step] as the newest step. The redo branch is discarded. */
    fun push(step: UndoStep) {
        undoSteps += step
        redoSteps.clear()
    }

    /**
     * Replaces the newest step by [step], because it grew by another change. Removes the newest step instead if [step]
     * is empty. The redo branch is discarded.
     */
    fun amendTop(step: UndoStep) {
        check(undoSteps.isNotEmpty()) { "no step to amend" }
        undoSteps.removeAt(undoSteps.lastIndex)
        if (!step.isEmpty) undoSteps += step
        redoSteps.clear()
    }

    /** Moves the newest step to the redo stack, because it has been undone. */
    fun moveTopToRedo() {
        redoSteps += undoSteps.removeAt(undoSteps.lastIndex)
    }

    /** Moves the newest undone step back to the undo stack, because it has been redone. */
    fun moveTopToUndo() {
        undoSteps += redoSteps.removeAt(redoSteps.lastIndex)
    }

    /** Records the state with identity [state] as the saved state. It may be the current state or an earlier one. */
    fun markSaved(state: Any) {
        savedState = state
    }

    /** Makes no state the saved state, by saving a fresh identity that no reachable state has. */
    fun invalidateSaved() {
        savedState = Any()
    }

    /**
     * Forgets both stacks. The current state becomes the base state and keeps its identity, so a save point on it
     * survives.
     */
    fun clear() {
        baseState = currentState
        undoSteps.clear()
        redoSteps.clear()
    }
}
