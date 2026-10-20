package io.karpfen.ide.core.text

/**
 * One undo step (Memento): what one or more consecutive changes did to the text, as a [forward] script on the text
 * before the step and a [backward] script on the text after it, plus the carets to restore.
 *
 * A step made of several changes (a group, a coalesced typing run) is still one forward and one backward script, so
 * undo and redo each apply exactly one change. Immutable: [extendedBy] returns a new step; the undo history uses the
 * step's identity as the identity of the text state it leads to.
 *
 * @property label what the step was made by (the group name or the origin of its first change), for diagnostics.
 * @property forward redoes the step; edits in the coordinates of the text before it.
 * @property backward undoes the step; edits in the coordinates of the text after it.
 * @property caretsBefore the `caretsBefore` of the step's first change (restored by undo).
 * @property caretsAfter the `caretsAfter` of the step's last change (restored by redo).
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

    /** True if the changes of this step cancelled each other out (the text is the same as before the step). */
    val isEmpty: Boolean get() = forward.isEmpty

    /**
     * This step followed by [change] (applied to the text after this step) as one step: the carets before stay, the
     * carets after become [caretsAfter] (also when null: a missing memento is not taken from another change).
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
