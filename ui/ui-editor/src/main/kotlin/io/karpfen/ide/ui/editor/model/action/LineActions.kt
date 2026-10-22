package io.karpfen.ide.ui.editor.model.action

/**
 * Kinds of [LineAction].
 *
 * @property actionId the id of the action of this kind.
 */
internal enum class LineOperation(val actionId: String) {
    DUPLICATE(EditorActionIds.DUPLICATE),
    MOVE_UP(EditorActionIds.MOVE_LINE_UP),
    MOVE_DOWN(EditorActionIds.MOVE_LINE_DOWN),
    INDENT(EditorActionIds.INDENT),
    OUTDENT(EditorActionIds.OUTDENT),
}

/**
 * Line operations: duplicate line (or selection), move line(s) up/down, indent/outdent by 4 spaces
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
 * Toggles `//` line comments; inside an EVAL body `#` (there `//` is a karpfen comment). Decided per line:
 * a line gets `#` iff `context.tokens.embeddedRegionAt(o)` is a body whose opening brace lies before the toggled block
 * (its content starts at or before the block's first line), for `o` = the offset of its first non-blank character (its
 * start when blank). So the `EVAL {` line itself gets `//`, the body's lines (blank lines, lines holding only a comment
 * and the line of the closing `}` included) get `#` — unless the block holds the `EVAL {` line as well: commenting that
 * line out ends the body, so its lines are no Python any more and get `//` like it (a `#` outside a body is no
 * comment, and the next toggle would not recognize it). The context's tokens always belong to the current text
 * (`EditorActionContext.tokens`), so the decision holds right after an edit, too, and the next toggle decides the same
 * markers on the commented text, which makes toggling twice restore the block.
 *
 * The edits themselves are [LineComments]: uncomment when every non-blank line of the block starts with its marker,
 * comment otherwise.
 */
internal class ToggleCommentAction : EditorAction {
    override val id: String = EditorActionIds.TOGGLE_COMMENT

    override fun execute(context: EditorActionContext): Boolean {
        val tokens = context.tokens
        val markers = CommentMarkers { offset, blockStart ->
            val body = tokens.embeddedRegionAt(offset)
            if (body != null && body.start <= blockStart) PYTHON_COMMENT else KARPFEN_COMMENT
        }
        return LineComments(markers, EditorActionTitles.of(id)).execute(context)
    }

    /** The comment markers of the two languages a KStates file mixes. */
    private companion object {
        /** A karpfen line comment, also inside an EVAL body, where it drops the rest of the line. */
        const val KARPFEN_COMMENT = "//"

        /** A Python line comment, which comments only inside an EVAL body. */
        const val PYTHON_COMMENT = "#"
    }
}

/** One line operation (Strategy of [LineAction] and [ToggleCommentAction]); runs it on the primary caret's block. */
internal fun interface LineEdit {
    /** Applies the operation as one change; false if there was nothing to do. */
    fun execute(context: EditorActionContext): Boolean
}
