package io.karpfen.ide.core.text

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default [UndoManager] with transactions (groups), coalescing of typing and caret mementos (Memento pattern).
 * Owned by a [DefaultEditableText], which reports every applied change through [record]; undo and redo apply the
 * step's backward or forward script through the [target] as exactly one change.
 *
 * Collaborators: [UndoHistory] (stacks and save point), [UndoStep] (the mementos), [coalescer] (when typing merges).
 * The step being built — the open group's step or the current typing run's step — is always the top of the undo
 * stack, and every change to it replaces that top with a new [UndoStep].
 *
 * Refinements of [UndoManager]: a new text is at its save point (the initial text counts as saved); [markSavePoint]
 * and [checkpoint] end the coalescing run so the state stays reachable (inside a group it is unreachable once the group
 * applies another change); a group whose changes cancel out leaves no step; [undo], [redo] and [clear] must not be called
 * inside [group] (they throw [IllegalStateException]). Like the text, it is driven from the single writer thread; its
 * flows may be read from anywhere.
 */
internal class DefaultUndoManager(
    private val target: EditTarget,
    private val coalescer: TypingCoalescer,
) : UndoManager {

    private val history = UndoHistory()
    private val canUndoState = MutableStateFlow(false)
    private val canRedoState = MutableStateFlow(false)
    private val savePointState = MutableStateFlow(true)

    /** The last keystroke of the typing run the top step may still absorb, or null. */
    private var lastKeystroke: Keystroke? = null

    private var groupDepth = 0
    private var groupName = ""

    /** True while the open group has a step on top of the undo stack that its next change extends. */
    private var groupHasStep = false

    override val canUndo: StateFlow<Boolean> = canUndoState.asStateFlow()

    override val canRedo: StateFlow<Boolean> = canRedoState.asStateFlow()

    override val isAtSavePoint: StateFlow<Boolean> = savePointState.asStateFlow()

    /**
     * Records [change], just applied to the text, with the carets the caller passed to `apply`: it becomes a new
     * step, extends the open group's step, or coalesces with the current typing run.
     */
    fun record(change: TextChange, caretsBefore: List<CaretMemento>?, caretsAfter: List<CaretMemento>?) {
        if (groupDepth > 0) {
            recordInGroup(change, caretsBefore, caretsAfter)
        } else {
            val keystroke = coalescer.keystrokeOf(change)
            val previous = lastKeystroke
            val top = history.undoTop
            if (keystroke != null && previous != null && top != null && coalescer.continues(previous, keystroke)) {
                history.amendTop(top.extendedBy(change, caretsAfter))
            } else {
                history.push(UndoStep.of(change.origin.label(), change, caretsBefore, caretsAfter))
            }
            lastKeystroke = keystroke
        }
        publishState()
    }

    override fun undo(): UndoResult? {
        check(groupDepth == 0) { "undo inside an undo group" }
        val step = history.undoTop ?: return null
        val change = commit(step.backward, EditOrigin.Undo)
        history.moveTopToRedo()
        return finish(UndoResult(change, step.caretsBefore))
    }

    override fun redo(): UndoResult? {
        check(groupDepth == 0) { "redo inside an undo group" }
        val step = history.redoTop ?: return null
        val change = commit(step.forward, EditOrigin.Redo)
        history.moveTopToUndo()
        return finish(UndoResult(change, step.caretsAfter))
    }

    override fun <T> group(name: String, block: () -> T): T {
        if (groupDepth == 0) {
            groupName = name
            groupHasStep = false
            lastKeystroke = null
        }
        groupDepth++
        try {
            return block()
        } finally {
            groupDepth--
            if (groupDepth == 0) groupHasStep = false
        }
    }

    override fun breakCoalescing() {
        lastKeystroke = null
    }

    override fun markSavePoint() {
        markSavePoint(checkpoint())
    }

    override fun checkpoint(): HistoryState {
        lastKeystroke = null
        return HistoryState(history.currentState)
    }

    override fun markSavePoint(state: HistoryState) {
        history.markSaved(state.identity)
        publishState()
    }

    override fun invalidateSavePoint() {
        history.invalidateSaved()
        publishState()
    }

    override fun clear() {
        check(groupDepth == 0) { "clear inside an undo group" }
        lastKeystroke = null
        history.clear()
        publishState()
    }

    /**
     * Records [change] inside the open group: the first change pushes the group's step, every further one extends it.
     * Changes that cancel each other out leave no step, and the group's next change then starts a new one.
     */
    private fun recordInGroup(change: TextChange, caretsBefore: List<CaretMemento>?, caretsAfter: List<CaretMemento>?) {
        val top = history.undoTop
        if (groupHasStep && top != null) {
            val extended = top.extendedBy(change, caretsAfter)
            history.amendTop(extended)
            groupHasStep = !extended.isEmpty
        } else {
            history.push(UndoStep.of(groupName, change, caretsBefore, caretsAfter))
            groupHasStep = true
        }
    }

    /** Applies a step's script; a step is never empty, so the text always changes. */
    private fun commit(script: EditScript, origin: EditOrigin): TextChange {
        lastKeystroke = null
        return checkNotNull(target.commit(script.edits, origin)) { "an undo step changed nothing" }
    }

    private fun finish(result: UndoResult): UndoResult {
        publishState()
        target.publishPending()
        return result
    }

    private fun publishState() {
        canUndoState.value = history.canUndo
        canRedoState.value = history.canRedo
        savePointState.value = history.isAtSavePoint
    }

    private fun EditOrigin.label(): String = when (this) {
        is EditOrigin.Command -> "Command($name)"
        is EditOrigin.Programmatic -> "Programmatic($name)"
        else -> toString()
    }
}
