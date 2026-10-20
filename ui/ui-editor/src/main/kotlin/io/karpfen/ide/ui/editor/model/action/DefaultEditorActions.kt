package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.editor.model.caret.CaretMovement

/**
 * Every key-bound/menu editor action (spec §7.1), one instance per id. Wiring only: the action classes below group
 * cohesive behaviour (one class per group, parameterized by kind).
 */
public fun defaultEditorActions(): List<EditorAction> = buildList {
    val movements = listOf(
        CaretMovement.LEFT to (EditorActionIds.CARET_LEFT to EditorActionIds.SELECT_LEFT),
        CaretMovement.RIGHT to (EditorActionIds.CARET_RIGHT to EditorActionIds.SELECT_RIGHT),
        CaretMovement.UP to (EditorActionIds.CARET_UP to EditorActionIds.SELECT_UP),
        CaretMovement.DOWN to (EditorActionIds.CARET_DOWN to EditorActionIds.SELECT_DOWN),
        CaretMovement.WORD_LEFT to (EditorActionIds.CARET_WORD_LEFT to EditorActionIds.SELECT_WORD_LEFT),
        CaretMovement.WORD_RIGHT to (EditorActionIds.CARET_WORD_RIGHT to EditorActionIds.SELECT_WORD_RIGHT),
        CaretMovement.LINE_START to (EditorActionIds.CARET_LINE_START to EditorActionIds.SELECT_LINE_START),
        CaretMovement.LINE_END to (EditorActionIds.CARET_LINE_END to EditorActionIds.SELECT_LINE_END),
        CaretMovement.PAGE_UP to (EditorActionIds.CARET_PAGE_UP to EditorActionIds.SELECT_PAGE_UP),
        CaretMovement.PAGE_DOWN to (EditorActionIds.CARET_PAGE_DOWN to EditorActionIds.SELECT_PAGE_DOWN),
        CaretMovement.DOCUMENT_START to (EditorActionIds.CARET_DOCUMENT_START to EditorActionIds.SELECT_DOCUMENT_START),
        CaretMovement.DOCUMENT_END to (EditorActionIds.CARET_DOCUMENT_END to EditorActionIds.SELECT_DOCUMENT_END),
    )
    for ((movement, ids) in movements) {
        add(CaretMoveAction(ids.first, movement, extendSelection = false))
        add(CaretMoveAction(ids.second, movement, extendSelection = true))
    }
    add(SelectAllAction())
    add(EnterAction())
    DeletionKind.entries.forEach { add(DeletionAction(it)) }
    LineOperation.entries.forEach { add(LineAction(it)) }
    add(ToggleCommentAction())
    ClipboardOperation.entries.forEach { add(ClipboardAction(it)) }
    add(HistoryAction(redo = false))
    add(HistoryAction(redo = true))
    UiRequest.entries.forEach { add(UiRequestAction(it)) }
}
