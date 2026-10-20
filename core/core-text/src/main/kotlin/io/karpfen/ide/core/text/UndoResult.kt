package io.karpfen.ide.core.text

/**
 * Result of [UndoManager.undo] or [UndoManager.redo].
 *
 * @property change the change that undo/redo applied to the text (origin [EditOrigin.Undo] or [EditOrigin.Redo]).
 * @property carets the carets to restore, in the coordinates of `change.after`: for undo the carets recorded
 *   *before* the undone step, for redo the carets recorded *after* the redone step; null if none were recorded.
 */
public data class UndoResult(public val change: TextChange, public val carets: List<CaretMemento>?)
