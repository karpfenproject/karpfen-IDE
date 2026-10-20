package io.karpfen.ide.ui.editor.model.action

/**
 * Ids of the editor actions (spec §7.1). Menus (app), context menus (popups), keymaps and the workbench's command
 * forwarding use these constants, so every surface reaches the same action.
 */
public object EditorActionIds {
    // --- caret movement (".select" variants extend the selection) ------------------------------------------------
    public const val CARET_LEFT: String = "editor.caret.left"
    public const val CARET_RIGHT: String = "editor.caret.right"
    public const val CARET_UP: String = "editor.caret.up"
    public const val CARET_DOWN: String = "editor.caret.down"
    public const val CARET_WORD_LEFT: String = "editor.caret.wordLeft"
    public const val CARET_WORD_RIGHT: String = "editor.caret.wordRight"
    public const val CARET_LINE_START: String = "editor.caret.lineStart"
    public const val CARET_LINE_END: String = "editor.caret.lineEnd"
    public const val CARET_PAGE_UP: String = "editor.caret.pageUp"
    public const val CARET_PAGE_DOWN: String = "editor.caret.pageDown"
    public const val CARET_DOCUMENT_START: String = "editor.caret.documentStart"
    public const val CARET_DOCUMENT_END: String = "editor.caret.documentEnd"
    public const val SELECT_LEFT: String = "editor.caret.left.select"
    public const val SELECT_RIGHT: String = "editor.caret.right.select"
    public const val SELECT_UP: String = "editor.caret.up.select"
    public const val SELECT_DOWN: String = "editor.caret.down.select"
    public const val SELECT_WORD_LEFT: String = "editor.caret.wordLeft.select"
    public const val SELECT_WORD_RIGHT: String = "editor.caret.wordRight.select"
    public const val SELECT_LINE_START: String = "editor.caret.lineStart.select"
    public const val SELECT_LINE_END: String = "editor.caret.lineEnd.select"
    public const val SELECT_PAGE_UP: String = "editor.caret.pageUp.select"
    public const val SELECT_PAGE_DOWN: String = "editor.caret.pageDown.select"
    public const val SELECT_DOCUMENT_START: String = "editor.caret.documentStart.select"
    public const val SELECT_DOCUMENT_END: String = "editor.caret.documentEnd.select"
    public const val SELECT_ALL: String = "editor.selectAll"

    // --- typing and deletion -----------------------------------------------------------------------------------------
    /** Id shared by every [typeTextAction] instance (not key-bound; typed text arrives through text input). */
    public const val TYPE_TEXT: String = "editor.typeText"
    public const val ENTER: String = "editor.enter"
    public const val BACKSPACE: String = "editor.backspace"
    public const val DELETE: String = "editor.delete"
    public const val DELETE_WORD_LEFT: String = "editor.deleteWordLeft"
    public const val DELETE_WORD_RIGHT: String = "editor.deleteWordRight"
    public const val DELETE_LINE: String = "editor.deleteLine"

    // --- line operations ------------------------------------------------------------------------------------------------
    public const val DUPLICATE: String = "editor.duplicate"
    public const val MOVE_LINE_UP: String = "editor.moveLineUp"
    public const val MOVE_LINE_DOWN: String = "editor.moveLineDown"
    public const val INDENT: String = "editor.indent"
    public const val OUTDENT: String = "editor.outdent"
    public const val TOGGLE_COMMENT: String = "editor.toggleComment"

    // --- clipboard and history ----------------------------------------------------------------------------------------
    public const val COPY: String = "editor.copy"
    public const val CUT: String = "editor.cut"
    public const val PASTE: String = "editor.paste"
    public const val UNDO: String = "editor.undo"
    public const val REDO: String = "editor.redo"

    // --- UI requests -----------------------------------------------------------------------------------------------------
    public const val FIND: String = "editor.find"
    public const val REPLACE: String = "editor.replace"
    public const val GO_TO_LINE: String = "editor.goToLine"
    public const val COMPLETION: String = "editor.completion"
    public const val GO_TO_DEFINITION: String = "editor.goToDefinition"
}
