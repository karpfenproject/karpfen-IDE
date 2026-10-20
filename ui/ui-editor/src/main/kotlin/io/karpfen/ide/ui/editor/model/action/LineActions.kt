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
 *
 * Each operation is a strategy of its own ([LineDuplication], [LineMove], [LineIndent], [LineOutdent]); every one
 * applies exactly one change with origin `Command(title)` and caret mementos.
 */
internal class LineAction(private val operation: LineOperation) : EditorAction {
    override val id: String = operation.actionId

    private val strategy: LineEdit = when (operation) {
        LineOperation.DUPLICATE -> LineDuplication(EditorActionTitles.of(id))
        LineOperation.MOVE_UP -> LineMove(up = true, EditorActionTitles.of(id))
        LineOperation.MOVE_DOWN -> LineMove(up = false, EditorActionTitles.of(id))
        LineOperation.INDENT -> LineIndent(EditorActionTitles.of(id))
        LineOperation.OUTDENT -> LineOutdent(EditorActionTitles.of(id))
    }

    override fun execute(context: EditorActionContext): Boolean = strategy.execute(context)
}

/**
 * Toggles `//` line comments; inside an EVAL body `#` (there `//` is a karpfen comment, spec §7.1). Decided per line:
 * a line is inside an EVAL body iff `context.tokens.embeddedRegionAt(o)` is non-null for `o` = the offset of its first
 * non-blank character (its start when blank). So the `EVAL {` line itself gets `//`, the body's lines (blank lines,
 * lines holding only a comment and the line of the closing `}` included) get `#`. The context's tokens always belong to
 * the current text (`EditorActionContext.tokens`), so the decision holds right after an edit, too.
 *
 * The edits themselves are [LineComments]: uncomment when every non-blank line of the block starts with its marker,
 * comment otherwise.
 */
internal class ToggleCommentAction : EditorAction {
    override val id: String = EditorActionIds.TOGGLE_COMMENT

    override fun execute(context: EditorActionContext): Boolean {
        val tokens = context.tokens
        val markerFor = { offset: Int -> if (tokens.embeddedRegionAt(offset) != null) "#" else "//" }
        return LineComments(markerFor, EditorActionTitles.of(id)).execute(context)
    }
}

/** One line operation (Strategy of [LineAction] and [ToggleCommentAction]); runs it on the primary caret's block. */
internal fun interface LineEdit {
    /** Applies the operation as one change; false if there was nothing to do. */
    fun execute(context: EditorActionContext): Boolean
}
