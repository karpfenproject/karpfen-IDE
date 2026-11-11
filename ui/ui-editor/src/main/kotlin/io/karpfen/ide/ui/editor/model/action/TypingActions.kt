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

import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.LineSeparators
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Types [text] at the primary caret and replaces the selection. The change has origin `Typing` and is one undo step.
 *
 * Line separators in [text] are normalized to `\n`. A typed `\n` alone runs Enter ([EnterAction]) with auto-indent.
 *
 * Typing `"`, `(`, `[` or `{` also inserts the matching closer when the next character is whitespace, the line end or
 * a closing bracket. `[` closes like the other brackets because lists are everyday syntax in two of the edited
 * languages, the JSON of environment descriptors and the Python of EVAL bodies. A `"` auto-closes only outside a string
 * of its line ([StringLiterals]), so typing the closing quote of a string never inserts a pair.
 *
 * Typing a closer right before a closer that [AutoClosedPairs] tracks moves the caret over it instead of inserting it.
 * Closers the user typed are never tracked, so typing `)` before one of them inserts a new `)`. Auto-close and typing
 * over a closer apply only to a single typed character without a selection. Anything else is inserted as it is.
 */
internal class TypeTextAction(private val text: String) : EditorAction {
    override val id: String = EditorActionIds.TYPE_TEXT

    override fun execute(context: EditorActionContext): Boolean {
        val typed = LineSeparators.normalize(text).text
        if (typed.isEmpty()) return false
        if (typed == "\n") return lineBreak.execute(context)
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        if (typed.length == 1 && !caret.hasSelection) {
            val char = typed[0]
            val position = caret.position
            if (overTypes(context, snapshot, position, char)) {
                context.autoPairs.forget(position)
                context.caret.restore(listOf(CaretMemento.at(position + 1)))
                return true
            }
            val closer = CLOSER_OF[char]
            if (closer != null && autoCloses(snapshot, position, char)) {
                val inserted = context.applyEdit(TextEdit.insert(position, "$char$closer"), EditOrigin.Typing, position + 1)
                if (inserted) context.autoPairs.record(position, position + 1)
                return inserted
            }
        }
        val selection = caret.selection
        return context.applyEdit(TextEdit(selection, typed), EditOrigin.Typing, selection.start + typed.length)
    }

    /** Whether typing [char] at [position] moves over a tracked auto-inserted closer instead of inserting it. */
    private fun overTypes(context: EditorActionContext, snapshot: TextSnapshot, position: Int, char: Char): Boolean =
        char in CLOSER_OF.values && position < snapshot.length && snapshot[position] == char &&
            context.autoPairs.isAutoClosedCloser(position)

    /**
     * Whether typing the opener [char] at [position] inserts its closer as well. That is the case when the next
     * character is whitespace, the line end or a closing bracket, and when a `"` does not close a string of its line.
     */
    private fun autoCloses(snapshot: TextSnapshot, position: Int, char: Char): Boolean {
        val next = if (position < snapshot.length) snapshot[position] else '\n'
        val nextAllows = next.isWhitespace() || next in CLOSING_BRACKETS
        return nextAllows && (char != '"' || !StringLiterals.isInsideString(snapshot, position))
    }

    /** The auto-closed pairs and the shared Enter action. */
    private companion object {
        /** The closer auto-inserted after each opener. */
        val CLOSER_OF: Map<Char, Char> = mapOf('"' to '"', '(' to ')', '[' to ']', '{' to '}')

        /** The characters before which an opener auto-closes, besides whitespace and the line end. */
        const val CLOSING_BRACKETS: String = ")]}"

        /** What a typed line break runs. */
        val lineBreak = EnterAction()
    }
}

/**
 * Enter. It replaces the selection with a new line that has the indentation of the current line. After a line that ends
 * in a block opener, the new line gets 4 more spaces. Block openers are `{` and the `[` of a list. Lists open blocks
 * like braces because descriptors spread their lists over lines, one item per line, the way the descriptor template
 * writes them.
 *
 * The indentation is the leading whitespace of the caret's line up to the caret. Whether the line ends in an opener is
 * decided on the text before the caret, with trailing whitespace trimmed.
 *
 * If the caret sits between an opener and its own closer, as in `{}` or `[]`, the closer moves to a line of its own.
 * Whitespace around the caret is allowed. The whitespace before the closer is dropped and the caret lands on the
 * indented middle line. A closer of the other kind, as in `{|]`, stays where it is. The change has origin `Typing` and
 * is one undo step.
 */
internal class EnterAction : EditorAction {
    override val id: String = EditorActionIds.ENTER

    override fun execute(context: EditorActionContext): Boolean {
        val snapshot = context.text.snapshot.value
        val selection = context.caret.primary.selection
        val index = snapshot.lineIndex
        val before = snapshot.text.substring(index.lineStart(index.lineOf(selection.start)), selection.start)
        val after = snapshot.text.substring(selection.end, index.lineEnd(index.lineOf(selection.end)))
        val indent = Indentation.leadingWhitespace(before)
        val blockCloser = before.trimEnd().lastOrNull()?.let(BLOCK_CLOSER_OF::get)
        val inner = if (blockCloser != null) indent + Indentation.UNIT else indent
        val closerOffset = Indentation.leadingWhitespace(after).length
        val closesBlock = blockCloser != null && after.getOrNull(closerOffset) == blockCloser
        val caretAfter = selection.start + 1 + inner.length
        return if (closesBlock) {
            val replaced = TextRange(selection.start, selection.end + closerOffset)
            context.applyEdit(TextEdit(replaced, "\n$inner\n$indent"), EditOrigin.Typing, caretAfter)
        } else {
            context.applyEdit(TextEdit(selection, "\n$inner"), EditOrigin.Typing, caretAfter)
        }
    }

    /** The block openers. */
    private companion object {
        /** The closer of each opener after which Enter indents one level more. */
        val BLOCK_CLOSER_OF: Map<Char, Char> = mapOf('{' to '}', '[' to ']')
    }
}

/**
 * Kinds of [DeletionAction].
 *
 * @property actionId the id of the action of this kind.
 */
internal enum class DeletionKind(val actionId: String) {
    BACKSPACE(EditorActionIds.BACKSPACE),
    DELETE(EditorActionIds.DELETE),
    WORD_LEFT(EditorActionIds.DELETE_WORD_LEFT),
    WORD_RIGHT(EditorActionIds.DELETE_WORD_RIGHT),
    LINE(EditorActionIds.DELETE_LINE),
}

/**
 * The deletion actions. If there is a selection, Backspace, Delete and the word deletions remove the selection.
 *
 * Backspace and Delete remove one character, and a surrogate pair as a whole. They use origin `Typing`, so a run of
 * them coalesces into one undo step. Inside leading whitespace, Backspace removes the spaces back to the previous
 * 4-column stop. Between a tracked pair, as in `(|)`, Backspace removes both characters ([AutoClosedPairs]).
 *
 * Word deletion removes text up to the previous word start or the next word end, using the editor's single word rule
 * ([DeletionRules]). Line deletion removes the lines of the selection ([LineBlock]) and keeps the caret's column. Word
 * and line deletion are commands, so each one is an undo step of its own.
 */
internal class DeletionAction(private val kind: DeletionKind) : EditorAction {
    override val id: String = kind.actionId

    override fun execute(context: EditorActionContext): Boolean {
        val caret = context.caret.primary
        val snapshot = context.text.snapshot.value
        if (kind == DeletionKind.LINE) return deleteLines(context, snapshot)
        if (caret.hasSelection) return delete(context, caret.selection, EditOrigin.Typing)
        val position = caret.position
        val range = when (kind) {
            DeletionKind.BACKSPACE -> DeletionRules.backspace(snapshot, position, context.autoPairs)
            DeletionKind.DELETE -> DeletionRules.forward(snapshot, position)
            DeletionKind.WORD_LEFT -> DeletionRules.wordLeft(snapshot, position)
            DeletionKind.WORD_RIGHT -> DeletionRules.wordRight(snapshot, position)
            DeletionKind.LINE -> error("handled above")
        } ?: return false
        val origin = if (kind == DeletionKind.BACKSPACE || kind == DeletionKind.DELETE) {
            EditOrigin.Typing
        } else {
            EditOrigin.Command(EditorActionTitles.of(id))
        }
        return delete(context, range, origin)
    }

    /** Deletes [range] with [origin], leaving the caret at its start. */
    private fun delete(context: EditorActionContext, range: TextRange, origin: EditOrigin): Boolean =
        context.applyEdit(TextEdit.delete(range), origin, range.start)

    /** Deletes the caret's [LineBlock] as a command and keeps the caret's column on the line that takes its place. */
    private fun deleteLines(context: EditorActionContext, snapshot: TextSnapshot): Boolean {
        val caret = context.caret.primary
        val block = LineBlock.of(snapshot, caret)
        val column = snapshot.positionOf(caret.position).column
        val caretAfter = block.caretAfterDeletion(snapshot, column)
        return context.applyEdit(TextEdit.delete(block.deletionRange(snapshot)), EditOrigin.Command(EditorActionTitles.of(id)), caretAfter)
    }
}
