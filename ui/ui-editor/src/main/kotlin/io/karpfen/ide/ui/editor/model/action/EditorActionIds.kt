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
package io.karpfen.ide.ui.editor.model.action

/**
 * Ids of the editor actions. Menus (app), context menus (popups), keymaps and the workbench's command forwarding use
 * these constants, so every surface reaches the same action.
 *
 * The ids come in groups: caret movement (`editor.caret.*`, whose `.select` variants extend the selection instead of
 * collapsing it), typing and deletion, line operations, clipboard and history, and UI requests (find, go to line,
 * completion, go to definition), which the editor state fulfils.
 */
public object EditorActionIds {
    /** Moves the caret one character left. */
    public const val CARET_LEFT: String = "editor.caret.left"

    /** Moves the caret one character right. */
    public const val CARET_RIGHT: String = "editor.caret.right"

    /** Moves the caret one line up, keeping its preferred display column. */
    public const val CARET_UP: String = "editor.caret.up"

    /** Moves the caret one line down, keeping its preferred display column. */
    public const val CARET_DOWN: String = "editor.caret.down"

    /** Moves the caret to the start of the word left of it (identifier, punctuation and whitespace runs are words). */
    public const val CARET_WORD_LEFT: String = "editor.caret.wordLeft"

    /** Moves the caret to the end of the word right of it. */
    public const val CARET_WORD_RIGHT: String = "editor.caret.wordRight"

    /** Smart Home: moves the caret to the first non-blank character of its line, then to column 0. */
    public const val CARET_LINE_START: String = "editor.caret.lineStart"

    /** Moves the caret to the end of its line. */
    public const val CARET_LINE_END: String = "editor.caret.lineEnd"

    /** Moves the caret one page of visible lines up. */
    public const val CARET_PAGE_UP: String = "editor.caret.pageUp"

    /** Moves the caret one page of visible lines down. */
    public const val CARET_PAGE_DOWN: String = "editor.caret.pageDown"

    /** Moves the caret to the start of the document. */
    public const val CARET_DOCUMENT_START: String = "editor.caret.documentStart"

    /** Moves the caret to the end of the document. */
    public const val CARET_DOCUMENT_END: String = "editor.caret.documentEnd"

    /** [CARET_LEFT], extending the selection. */
    public const val SELECT_LEFT: String = "editor.caret.left.select"

    /** [CARET_RIGHT], extending the selection. */
    public const val SELECT_RIGHT: String = "editor.caret.right.select"

    /** [CARET_UP], extending the selection. */
    public const val SELECT_UP: String = "editor.caret.up.select"

    /** [CARET_DOWN], extending the selection. */
    public const val SELECT_DOWN: String = "editor.caret.down.select"

    /** [CARET_WORD_LEFT], extending the selection. */
    public const val SELECT_WORD_LEFT: String = "editor.caret.wordLeft.select"

    /** [CARET_WORD_RIGHT], extending the selection. */
    public const val SELECT_WORD_RIGHT: String = "editor.caret.wordRight.select"

    /** [CARET_LINE_START], extending the selection. */
    public const val SELECT_LINE_START: String = "editor.caret.lineStart.select"

    /** [CARET_LINE_END], extending the selection. */
    public const val SELECT_LINE_END: String = "editor.caret.lineEnd.select"

    /** [CARET_PAGE_UP], extending the selection. */
    public const val SELECT_PAGE_UP: String = "editor.caret.pageUp.select"

    /** [CARET_PAGE_DOWN], extending the selection. */
    public const val SELECT_PAGE_DOWN: String = "editor.caret.pageDown.select"

    /** [CARET_DOCUMENT_START], extending the selection. */
    public const val SELECT_DOCUMENT_START: String = "editor.caret.documentStart.select"

    /** [CARET_DOCUMENT_END], extending the selection. */
    public const val SELECT_DOCUMENT_END: String = "editor.caret.documentEnd.select"

    /** Selects the whole document. */
    public const val SELECT_ALL: String = "editor.selectAll"

    /** Id shared by every [typeTextAction] instance (not key-bound; typed text arrives through text input). */
    public const val TYPE_TEXT: String = "editor.typeText"

    /** Breaks the line with auto-indent. */
    public const val ENTER: String = "editor.enter"

    /** Deletes the selection or the character left of the caret, with smart unindent and pair deletion. */
    public const val BACKSPACE: String = "editor.backspace"

    /** Deletes the selection or the character right of the caret. */
    public const val DELETE: String = "editor.delete"

    /** Deletes the selection or back to the start of the word left of the caret. */
    public const val DELETE_WORD_LEFT: String = "editor.deleteWordLeft"

    /** Deletes the selection or up to the end of the word right of the caret. */
    public const val DELETE_WORD_RIGHT: String = "editor.deleteWordRight"

    /** Deletes the lines of the selection or of the caret. */
    public const val DELETE_LINE: String = "editor.deleteLine"

    /** Duplicates the selection, or the lines of the caret without a selection. */
    public const val DUPLICATE: String = "editor.duplicate"

    /** Moves the lines of the selection or of the caret one line up. */
    public const val MOVE_LINE_UP: String = "editor.moveLineUp"

    /** Moves the lines of the selection or of the caret one line down. */
    public const val MOVE_LINE_DOWN: String = "editor.moveLineDown"

    /**
     * Indents every non-empty line of a selection spanning lines by one level; otherwise replaces the selection by
     * spaces up to the next indentation stop.
     */
    public const val INDENT: String = "editor.indent"

    /** Removes one indentation level from the lines of the selection or of the caret. */
    public const val OUTDENT: String = "editor.outdent"

    /** Comments or uncomments the lines of the selection or of the caret. */
    public const val TOGGLE_COMMENT: String = "editor.toggleComment"

    /** Copies the selection, or the caret's whole line without a selection. */
    public const val COPY: String = "editor.copy"

    /** Cuts the selection, or the caret's whole line without a selection. */
    public const val CUT: String = "editor.cut"

    /** Pastes the clipboard text (a copied whole line above the caret's line). */
    public const val PASTE: String = "editor.paste"

    /** Undoes the last undo step and restores its carets. */
    public const val UNDO: String = "editor.undo"

    /** Redoes the last undone step and restores its carets. */
    public const val REDO: String = "editor.redo"

    /** Opens the find bar. */
    public const val FIND: String = "editor.find"

    /** Opens the find bar with its replace row. */
    public const val REPLACE: String = "editor.replace"

    /** Selects the next match of the find query (opens the find bar while the query is empty). */
    public const val FIND_NEXT: String = "editor.findNext"

    /** Selects the previous match of the find query (opens the find bar while the query is empty). */
    public const val FIND_PREVIOUS: String = "editor.findPrevious"

    /** Opens the go-to-line popup. */
    public const val GO_TO_LINE: String = "editor.goToLine"

    /** Opens code completion at the caret. */
    public const val COMPLETION: String = "editor.completion"

    /** Navigates to the definition of the symbol at the caret. */
    public const val GO_TO_DEFINITION: String = "editor.goToDefinition"
}

/**
 * User-visible titles of the editor actions: the one source of the labels shown by the editor context menu (popups)
 * and by the window menu (the workbench registers the editor commands with these titles), so both always agree.
 * Every id of [EditorActionIds] has a title.
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
