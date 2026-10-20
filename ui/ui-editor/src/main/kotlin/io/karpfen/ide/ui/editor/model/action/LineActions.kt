package io.karpfen.ide.ui.editor.model.action

/** Kinds of [LineAction]. */
internal enum class LineOperation(val actionId: String) {
    DUPLICATE(EditorActionIds.DUPLICATE),
    MOVE_UP(EditorActionIds.MOVE_LINE_UP),
    MOVE_DOWN(EditorActionIds.MOVE_LINE_DOWN),
    INDENT(EditorActionIds.INDENT),
    OUTDENT(EditorActionIds.OUTDENT),
}

/**
 * Line operations (spec §7.1): duplicate line (or selection), move line(s) up/down, indent/outdent by 4 spaces
 * (Tab/Shift+Tab; Tab without a multi-line selection inserts spaces up to the next 4-column stop).
 */
internal class LineAction(private val operation: LineOperation) : EditorAction {
    override val id: String = operation.actionId

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: ${operation.name}")
}

/** Toggles `//` line comments; inside an EVAL body `#` (there `//` is a karpfen comment, spec §7.1). */
internal class ToggleCommentAction : EditorAction {
    override val id: String = EditorActionIds.TOGGLE_COMMENT

    override fun execute(context: EditorActionContext): Boolean = TODO("WP-EDITOR-MODEL: toggle comment")
}
