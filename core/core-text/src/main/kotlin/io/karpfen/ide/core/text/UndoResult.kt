package io.karpfen.ide.core.text

/**
 * Result of [UndoManager.undo] or [UndoManager.redo].
 *
 * @property change the change that undo/redo applied to the text (origin [EditOrigin.Undo] or [EditOrigin.Redo]).
 * @property carets the carets to restore, in the coordinates of `change.after`: for undo the carets recorded
 *   *before* the undone step (the `caretsBefore` of its first change), for redo the carets recorded *after* the redone
 *   step (the `caretsAfter` of its last change); null if that change recorded none.
 */
public data class UndoResult(public val change: TextChange, public val carets: List<CaretMemento>?)
