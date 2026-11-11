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

import io.karpfen.ide.core.document.syntax.KarpfenSyntax

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
 * The line operations. They duplicate the line or the selection, move lines up or down, and indent or outdent by 4
 * spaces with Tab and Shift+Tab. Tab without a multi-line selection inserts spaces up to the next 4-column stop
 * instead.
 *
 * Each operation is a strategy of its own: [LineDuplication], [LineMove], [LineIndent] and [LineOutdent]. Every one
 * applies exactly one change with origin `Command(title)` and records caret mementos.
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
 * Toggles line comments. The marker is `//`, but inside an EVAL body it is `#`, because there `//` is still a karpfen
 * comment.
 *
 * The marker is chosen per line. Take the offset of the line's first non-blank character, or the line start for a
 * blank line. The line gets `#` only if `context.tokens.embeddedRegionAt` returns a body for that offset whose opening
 * brace lies before the toggled block, which means the body content starts at or before the first line of the block.
 * So the `EVAL {` line itself gets `//`. The lines of the body get `#`, including blank lines, lines that hold only a
 * comment, and the line of the closing `}`. The exception is a block that also holds the `EVAL {` line. Commenting that
 * line out ends the body, so its lines are no longer Python and get `//` like it. A `#` outside a body is not a
 * comment, and the next toggle would not recognize it.
 *
 * The tokens of the context always belong to the current text (`EditorActionContext.tokens`). So the decision also
 * holds right after an edit, and the next toggle chooses the same markers on the commented text. Toggling twice
 * therefore restores the block.
 *
 * [LineComments] computes the edits. It uncomments when every non-blank line of the block starts with its marker and
 * comments otherwise. Outside EVAL bodies the marker is the language's own (`EditorActionContext.lineComment`). In a
 * language without comments, such as the environment descriptors, the action does nothing.
 */
internal class ToggleCommentAction : EditorAction {
    override val id: String = EditorActionIds.TOGGLE_COMMENT

    /**
     * Enabled in a language with line comments. The marker is fixed for the life of an editor, so this never goes
     * stale. Menus grey the action out in environment descriptors.
     */
    override fun isEnabled(context: EditorActionContext): Boolean = context.lineComment != null

    override fun execute(context: EditorActionContext): Boolean {
        val lineComment = context.lineComment ?: return false
        val tokens = context.tokens
        val markers = CommentMarkers { offset, blockStart ->
            val body = tokens.embeddedRegionAt(offset)
            if (body != null && body.start <= blockStart) KarpfenSyntax.PYTHON_LINE_COMMENT else lineComment
        }
        return LineComments(markers, EditorActionTitles.of(id)).execute(context)
    }
}

/**
 * One line operation, run on the block of the primary caret. It is the strategy that [LineAction] and
 * [ToggleCommentAction] use.
 */
internal fun interface LineEdit {
    /** Applies the operation as one change. Returns false if there was nothing to do. */
    fun execute(context: EditorActionContext): Boolean
}
