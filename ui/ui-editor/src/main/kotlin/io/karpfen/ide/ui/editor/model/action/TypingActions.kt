package io.karpfen.ide.ui.editor.model.action

/**
 * Types [text] (spec §7.1): replaces selections; auto-closes `"`, `(`, `{` when the next character is whitespace,
 * end of line or a closing bracket, and over-types an auto-inserted closer. Origin `Typing`, one undo step.
 */
internal class TypeTextAction(private val text: String) : EditorAction {
    override val id: String = EditorActionIds.TYPE_TEXT

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: type text")
}

/**
 * Enter (spec §7.1): new line with the indentation of the current line, +4 spaces after a line ending in `{`; between
 * `{` and `}` the `}` moves to its own line.
 */
internal class EnterAction : EditorAction {
    override val id: String = EditorActionIds.ENTER

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: enter with auto-indent")
}

/** Kinds of [DeletionAction]. */
internal enum class DeletionKind(val actionId: String) {
    BACKSPACE(EditorActionIds.BACKSPACE),
    DELETE(EditorActionIds.DELETE),
    WORD_LEFT(EditorActionIds.DELETE_WORD_LEFT),
    WORD_RIGHT(EditorActionIds.DELETE_WORD_RIGHT),
    LINE(EditorActionIds.DELETE_LINE),
}

/**
 * Deletion (spec §7.1): selection first; Backspace smart-unindents to the previous 4-column stop inside leading
 * whitespace and deletes an auto-inserted pair; word and line deletion.
 */
internal class DeletionAction(private val kind: DeletionKind) : EditorAction {
    override val id: String = kind.actionId

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: ${kind.name}")
}
