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

    /** Selects the next match of the find query (opens the find bar while the query is empty). */
    public const val FIND_NEXT: String = "editor.findNext"

    /** Selects the previous match of the find query (opens the find bar while the query is empty). */
    public const val FIND_PREVIOUS: String = "editor.findPrevious"
    public const val GO_TO_LINE: String = "editor.goToLine"
    public const val COMPLETION: String = "editor.completion"
    public const val GO_TO_DEFINITION: String = "editor.goToDefinition"
}

/**
 * User-visible titles of the editor actions: the one source of the labels shown by the editor context menu (popups)
 * and by the window menu (the workbench registers the editor commands with these titles), so both always agree.
 * Complete in the contracts step: every id of [EditorActionIds] has a title.
 */
public object EditorActionTitles {

    /** Title by action id. */
    public val titles: Map<String, String> = mapOf(
        EditorActionIds.CARET_LEFT to "Move Left",
        EditorActionIds.CARET_RIGHT to "Move Right",
        EditorActionIds.CARET_UP to "Move Up",
        EditorActionIds.CARET_DOWN to "Move Down",
        EditorActionIds.CARET_WORD_LEFT to "Move to Previous Word",
        EditorActionIds.CARET_WORD_RIGHT to "Move to Next Word",
        EditorActionIds.CARET_LINE_START to "Move to Line Start",
        EditorActionIds.CARET_LINE_END to "Move to Line End",
        EditorActionIds.CARET_PAGE_UP to "Page Up",
        EditorActionIds.CARET_PAGE_DOWN to "Page Down",
        EditorActionIds.CARET_DOCUMENT_START to "Move to Document Start",
        EditorActionIds.CARET_DOCUMENT_END to "Move to Document End",
        EditorActionIds.SELECT_LEFT to "Select Left",
        EditorActionIds.SELECT_RIGHT to "Select Right",
        EditorActionIds.SELECT_UP to "Select Up",
        EditorActionIds.SELECT_DOWN to "Select Down",
        EditorActionIds.SELECT_WORD_LEFT to "Select to Previous Word",
        EditorActionIds.SELECT_WORD_RIGHT to "Select to Next Word",
        EditorActionIds.SELECT_LINE_START to "Select to Line Start",
        EditorActionIds.SELECT_LINE_END to "Select to Line End",
        EditorActionIds.SELECT_PAGE_UP to "Select Page Up",
        EditorActionIds.SELECT_PAGE_DOWN to "Select Page Down",
        EditorActionIds.SELECT_DOCUMENT_START to "Select to Document Start",
        EditorActionIds.SELECT_DOCUMENT_END to "Select to Document End",
        EditorActionIds.SELECT_ALL to "Select All",
        EditorActionIds.TYPE_TEXT to "Type",
        EditorActionIds.ENTER to "New Line",
        EditorActionIds.BACKSPACE to "Delete Left",
        EditorActionIds.DELETE to "Delete Right",
        EditorActionIds.DELETE_WORD_LEFT to "Delete Previous Word",
        EditorActionIds.DELETE_WORD_RIGHT to "Delete Next Word",
        EditorActionIds.DELETE_LINE to "Delete Line",
        EditorActionIds.DUPLICATE to "Duplicate Line",
        EditorActionIds.MOVE_LINE_UP to "Move Line Up",
        EditorActionIds.MOVE_LINE_DOWN to "Move Line Down",
        EditorActionIds.INDENT to "Indent",
        EditorActionIds.OUTDENT to "Outdent",
        EditorActionIds.TOGGLE_COMMENT to "Toggle Comment",
        EditorActionIds.COPY to "Copy",
        EditorActionIds.CUT to "Cut",
        EditorActionIds.PASTE to "Paste",
        EditorActionIds.UNDO to "Undo",
        EditorActionIds.REDO to "Redo",
        EditorActionIds.FIND to "Find…",
        EditorActionIds.REPLACE to "Replace…",
        EditorActionIds.FIND_NEXT to "Find Next",
        EditorActionIds.FIND_PREVIOUS to "Find Previous",
        EditorActionIds.GO_TO_LINE to "Go to Line…",
        EditorActionIds.COMPLETION to "Complete Code",
        EditorActionIds.GO_TO_DEFINITION to "Go to Definition",
    )

    /**
     * The title of [actionId].
     * @throws IllegalArgumentException for an id without a title (programming error).
     */
    public fun of(actionId: String): String = requireNotNull(titles[actionId]) { "no title for editor action $actionId" }
}
