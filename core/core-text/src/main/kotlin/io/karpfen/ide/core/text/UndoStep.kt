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
 * One undo step, a memento in the sense of the Memento pattern. It records what one or more consecutive changes did to
 * the text, as a [forward] script on the text before the step and a [backward] script on the text after it. It also
 * holds the carets to restore.
 *
 * A step made of several changes, such as a group or a coalesced typing run, is still one forward and one backward
 * script, so undo and redo each apply exactly one change. A step is immutable, and [extendedBy] returns a new step.
 * The undo history uses the step's identity as the identity of the text state it leads to.
 *
 * @property label what made the step, for diagnostics. It is the group name or the origin of its first change.
 * @property forward redoes the step. Its edits are in the coordinates of the text before the step.
 * @property backward undoes the step. Its edits are in the coordinates of the text after the step.
 * @property caretsBefore the `caretsBefore` of the step's first change. Undo restores them.
 * @property caretsAfter the `caretsAfter` of the step's last change. Redo restores them.
 */
internal class UndoStep private constructor(
    val label: String,
    val forward: EditScript,
    val backward: EditScript,
    private val lengthBefore: Int,
    private val lengthAfter: Int,
    val caretsBefore: List<CaretMemento>?,
    val caretsAfter: List<CaretMemento>?,
) {

    /** True if the changes of this step cancelled each other out, so the text is the same as before the step. */
    val isEmpty: Boolean get() = forward.isEmpty

    /**
     * This step followed by [change] as one step. [change] was applied to the text after this step. The carets before
     * stay, and the carets after become [caretsAfter]. That holds even when [caretsAfter] is null, because a missing
     * memento is never taken from another change.
     */
    fun extendedBy(change: TextChange, caretsAfter: List<CaretMemento>?): UndoStep {
        require(change.before.length == lengthAfter) { "the change does not continue this step" }
        val script = EditScript.of(change.edits, change.before.length)
        return UndoStep(
            label = label,
            forward = forward.then(script, lengthBefore),
            backward = script.inverted(change.before.text).then(backward, change.after.length),
            lengthBefore = lengthBefore,
            lengthAfter = change.after.length,
            caretsBefore = caretsBefore,
            caretsAfter = caretsAfter?.toList(),
        )
    }

    override fun toString(): String = "UndoStep($label, forward=$forward)"

    companion object {
        /** The step of the single [change], labelled [label]. */
        fun of(
            label: String,
            change: TextChange,
            caretsBefore: List<CaretMemento>?,
            caretsAfter: List<CaretMemento>?,
        ): UndoStep {
            val script = EditScript.of(change.edits, change.before.length)
            return UndoStep(
                label = label,
                forward = script,
                backward = script.inverted(change.before.text),
                lengthBefore = change.before.length,
                lengthAfter = change.after.length,
                caretsBefore = caretsBefore?.toList(),
                caretsAfter = caretsAfter?.toList(),
            )
        }
    }
}
