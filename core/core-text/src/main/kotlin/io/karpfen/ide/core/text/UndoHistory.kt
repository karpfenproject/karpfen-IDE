package io.karpfen.ide.core.text

/**
 * The undo and redo stacks of one text and its save point.
 *
 * Every text state the history can reach has an identity: the step that led to it (the top of the undo stack) or,
 * with an empty undo stack, the identity of the base state. The save point is such an identity, so [isAtSavePoint]
 * is a reference comparison; a state that can no longer be reached (its step was amended, or lay on a discarded redo
 * branch) keeps an identity nobody will ever have again. Plain data structure of the [DefaultUndoManager]; not
 * thread-safe.
 */
internal class UndoHistory {

    private val undoSteps = ArrayList<UndoStep>()
    private val redoSteps = ArrayList<UndoStep>()

    /** Identity of the state below the oldest undo step. */
    private var baseState: Any = Any()

    /** Identity of the saved state; the initial state is the saved state. */
    private var savedState: Any = baseState

    /** Identity of the current state. */
    val currentState: Any get() = undoSteps.lastOrNull() ?: baseState

    /** True if there is a step to undo. */
    val canUndo: Boolean get() = undoSteps.isNotEmpty()

    /** True if there is a step to redo. */
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    /** True if the current state is the saved state. */
    val isAtSavePoint: Boolean get() = currentState === savedState

    /** The step an undo reverts (the newest step), or null. */
    val undoTop: UndoStep? get() = undoSteps.lastOrNull()

    /** The step a redo re-applies (the newest undone step), or null. */
    val redoTop: UndoStep? get() = redoSteps.lastOrNull()

    /** Records [step] as the newest step; the redo branch is discarded. */
    fun push(step: UndoStep) {
        undoSteps += step
        redoSteps.clear()
    }

    /**
     * Replaces the newest step by [step] (it grew by another change), or removes it if [step] is empty; the redo
     * branch is discarded.
     */
    fun amendTop(step: UndoStep) {
        check(undoSteps.isNotEmpty()) { "no step to amend" }
        undoSteps.removeAt(undoSteps.lastIndex)
        if (!step.isEmpty) undoSteps += step
        redoSteps.clear()
    }

    /** Moves the newest step to the redo stack (it has been undone). */
    fun moveTopToRedo() {
        redoSteps += undoSteps.removeAt(undoSteps.lastIndex)
    }

    /** Moves the newest undone step back to the undo stack (it has been redone). */
    fun moveTopToUndo() {
        undoSteps += redoSteps.removeAt(redoSteps.lastIndex)
    }

    /** Records the state with identity [state] (the current or an earlier one) as the saved state. */
    fun markSaved(state: Any) {
        savedState = state
    }

    /** Makes no state the saved state: a fresh identity that no reachable state has. */
    fun invalidateSaved() {
        savedState = Any()
    }

    /** Forgets both stacks; the current state becomes the base state and keeps its identity (and save point). */
    fun clear() {
        baseState = currentState
        undoSteps.clear()
        redoSteps.clear()
    }
}
