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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default [UndoManager] with groups as transactions, coalescing of typing, and caret mementos in the sense of the
 * Memento pattern. A [DefaultEditableText] owns it and reports every applied change through [record]. Undo and redo
 * apply the backward or forward script of a step through the [target] as exactly one change.
 *
 * [UndoHistory] holds the stacks and the save point, [UndoStep] is the memento, and the [coalescer] decides when typing
 * merges. The step being built is always the top of the undo stack. It is either the open group's step or the step of
 * the current typing run. Every change to it replaces that top with a new [UndoStep].
 *
 * The class adds these rules to [UndoManager]:
 * - A new text is at its save point, because the initial text counts as saved.
 * - [markSavePoint] and [checkpoint] end the coalescing run, so the state stays reachable. Inside a group the state
 *   becomes unreachable once the group applies another change.
 * - A group whose changes cancel out leaves no step.
 * - [undo], [redo] and [clear] must not be called inside [group]. They throw [IllegalStateException] there.
 *
 * Like the text, it is driven from the single writer thread. Its flows may be read from anywhere.
 */
internal class DefaultUndoManager(
    private val target: EditTarget,
    private val coalescer: TypingCoalescer,
) : UndoManager {

    private val history = UndoHistory()
    private val canUndoState = MutableStateFlow(false)
    private val canRedoState = MutableStateFlow(false)
    private val savePointState = MutableStateFlow(true)

    /** The last keystroke of the typing run that the top step may still absorb, or null. */
    private var lastKeystroke: Keystroke? = null

    private var groupDepth = 0
    private var groupName = ""

    /** True while the open group has a step on top of the undo stack that its next change extends. */
    private var groupHasStep = false

    override val canUndo: StateFlow<Boolean> = canUndoState.asStateFlow()

    override val canRedo: StateFlow<Boolean> = canRedoState.asStateFlow()

    override val isAtSavePoint: StateFlow<Boolean> = savePointState.asStateFlow()

    /**
     * Records [change], which was just applied to the text, with the carets the caller passed to `apply`. The change
     * becomes a new step, extends the open group's step, or coalesces with the current typing run.
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
     * Records [change] inside the open group. The first change pushes the group's step, and every further change
     * extends it. Changes that cancel each other out leave no step, and the group's next change then starts a new one.
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

    /** Applies the script of a step. A step is never empty, so the text always changes. */
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
